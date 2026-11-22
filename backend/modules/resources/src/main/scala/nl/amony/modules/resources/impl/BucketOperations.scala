package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.UUID
import scala.util.{Failure, Success}

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import org.apache.tika.Tika
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.ResourceContent.Streamed
import nl.amony.modules.resources.util.{ETag, SingleFlight}
import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile
import nl.amony.platform.subprocess.ffmpeg.{ContainerFormat, FFMpeg}
import nl.amony.platform.subprocess.magick.ImageMagick
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessRunner, ProcessSettings}

/** Where content to operate on lives: a resource in the bucket or a file staged for upload. */
enum ContentLocation:
  case Resource(path: ResourcePath)
  case Staged(stagingId: StagingId)

  def display: String = this match
    case Resource(path)    => path
    case Staged(stagingId) => s"staged:$stagingId"

/** Operations on the content of a bucket, addressed without exposing local file system paths. */
trait BucketOperations:

  def contentMeta(location: ContentLocation): EitherT[IO, ContentUnreadable, ContentMeta]

  def contentPartialHash(location: ContentLocation): EitherT[IO, ContentUnreadable, String]

  def normalizeUpload(stagingId: StagingId, container: VideoContainer): EitherT[IO, StreamabilityError, Unit]

  def remuxForStreaming(info: ResourceInfo): EitherT[IO, StreamabilityError, Option[StagingId]]

  def commitStreamability(info: ResourceInfo, stagingId: Option[StagingId]): EitherT[IO, StreamabilityError, ResourceInfo]

  def derivedResource(info: ResourceInfo, operation: ResourceOperation): EitherT[IO, DerivedResourceError, ResourceContent]

  def generatePreviews(info: ResourceInfo): IO[Unit]

final case class ContentUnreadable(location: ContentLocation, cause: Throwable):
  def message: String = s"Failed to read content of ${location.display}: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"

enum DerivedResourceError:
  case InvalidPath(resourceId: ResourceId, reason: String)
  case SourceMissing(resourceId: ResourceId, path: String)

  def message: String = this match
    case InvalidPath(resourceId, reason) => s"Ignoring resource '$resourceId' with invalid path: $reason"
    case SourceMissing(resourceId, path) => s"Resource '$resourceId' was found in the database but no file exists: $path"

final class FileOperations(
  config: LocalDirectoryConfig,
  parallelFactor: Int,
  formats: ThumbnailFormats,
  resolutions: ThumbnailResolutions,
  processSettings: ProcessSettings,
  processLimiter: ProcessLimiter
)(using meter: Meter[IO], tracer: Tracer[IO]) extends BucketOperations, Logging {

  private given runner: ProcessRunner = new ProcessRunner(processSettings, processLimiter)
  private val tika                    = new Tika()

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  extension (operation: ResourceOperation)
    def outputFile(resourceId: ResourceId, contentVersion: String): Path = {

      // Encode the pinned dimension so width- and height-based outputs never collide in the cache.
      def scaleSuffix(width: Option[Int], height: Option[Int]): String =
        width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

      val fileName = operation match
        case VideoFragment(width, height, start, end)                         => s"${resourceId}_${contentVersion}_$start-${end}_${scaleSuffix(width, height)}.mp4"
        case VideoThumbnail(width, height, timestamp, format)                 =>
          s"${resourceId}_${contentVersion}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
        case ImageThumbnail(width, height, format)                            => s"${resourceId}_${contentVersion}_${scaleSuffix(width, height)}.${format.extension}"
        case VideoThumbnailTile(spritePixels, intervalInMillis, index, token) =>
          VideoThumbnailTile.spriteFileName(resourceId, contentVersion, spritePixels, intervalInMillis, index, token)
        case VideoThumbnailTileVtt(spritePixels, intervalInMillis, token)     =>
          VideoThumbnailTile.manifestFileName(resourceId, contentVersion, spritePixels, intervalInMillis, token)
        case AddFastStart(_)                                                  =>
          throw new IllegalArgumentException("AddFastStart does not produce a derived output file")

      config.cachePath.resolve(fileName)
    }

  /**
   * Tracks running operations per resource and operation. Derived operations return None because
   * their output is read from the cache file; in-place operations return the refreshed resource.
   */
  private val inFlight = SingleFlight[OperationKey, Option[ResourceInfo]]

  private def pathOf(location: ContentLocation): Either[ContentUnreadable, Path] = location match
    case ContentLocation.Resource(path)    =>
      ResourcePath.fromString(path)
        .left.map(message => ContentUnreadable(location, new IllegalArgumentException(message)))
        .map(config.resourcePath.resolve(_))
    case ContentLocation.Staged(stagingId) => Right(config.tempPath.resolve(stagingId))

  private def contentTypeFor(location: ContentLocation, path: Path): EitherT[IO, ContentUnreadable, String] =
    EitherT(IO.blocking(tika.detect(path)).attempt.map(_.left.map(ContentUnreadable(location, _))))

  override def contentMeta(location: ContentLocation): EitherT[IO, ContentUnreadable, ContentMeta] =
    for
      path        <- EitherT.fromEither[IO](pathOf(location))
      contentType <- contentTypeFor(location, path)
      scan        <- EitherT.liftF(toolScanForPath(path, contentType))
      streamable  <- EitherT.liftF(streamableOf(path, Some(contentType)))
    yield scan.map(ContentMeta.scanned).getOrElse(ContentMeta.empty)
      .copy(contentType = Some(contentType), streamable = streamable)

  override def contentPartialHash(location: ContentLocation): EitherT[IO, ContentUnreadable, String] =
    for
      path <- EitherT.fromEither[IO](pathOf(location))
      hash <- EitherT(config.hashingAlgorithm.createHash(path).attempt.map(_.left.map(ContentUnreadable(location, _))))
    yield hash

  private def toolScanForPath(path: Path, contentType: String): IO[Option[ToolScan]] =
    if contentType.startsWith("video/") then
      FFMpeg.ffprobe(path.toUri, processSettings.ffprobeTimeout).map {
        (ffprobeResult, json) =>
          val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
          Some(ToolScan(s"ffprobe/$version", json))
      }.recover { case e: Throwable => logger.error(s"Failed to get video meta data for $path", e); None }
    else if contentType.startsWith("image/") then
      ImageMagick.getImageMeta(path).map:
        case Failure(e)      =>
          logger.error(s"Failed to get image meta data for $path", e)
          None
        case Success(result) =>
          Some(ToolScan("magick/1", result.rawJson))
    else IO.pure(None)

  private def addFastStart(video: Path, container: VideoContainer, outputFile: Path): IO[Path] =
    FFMpeg.addFastStart(video.toUri, containerFormat(container), outputFile)

  override def normalizeUpload(stagingId: StagingId, container: VideoContainer): EitherT[IO, StreamabilityError, Unit] =
    val source     = config.tempPath.resolve(stagingId)
    val normalized = config.tempPath.resolve(s"$stagingId-normalized")
    val normalize  =
      for
        _   <- IO.blocking(Files.createDirectories(config.tempPath))
        out <- addFastStart(source, container, normalized)
        _   <- moveIntoPlace(out, source)
      yield Right(()): Either[StreamabilityError, Unit]
    EitherT(
      normalize
        .onError(_ => IO.blocking(Files.deleteIfExists(normalized)).void)
        .attempt
        .map:
          case Right(result) => result
          case Left(error)   => Left(StreamabilityError.FixFailed(stagingId, s"Failed to normalize the staged upload: ${error.getMessage}"))
    )

  private def containerFormat(container: VideoContainer): ContainerFormat = container match
    case VideoContainer.Mp4      => ContainerFormat.Mp4
    case VideoContainer.Webm     => ContainerFormat.Webm
    case VideoContainer.Matroska => ContainerFormat.Matroska

  private[impl] def streamableOf(path: Path, contentType: Option[String]): IO[Option[Boolean]] =
    if contentType.exists(_.startsWith("video/")) then
      Streamability.detect(path).map {
        case Streamability.Streamable    => Some(true)
        case Streamability.NotStreamable => Some(false)
        case Streamability.Unknown       => None
      }
    else IO.pure(None)

  /**
   * Remuxes the resource into the bucket's temp directory without touching the live file, so a failed or abandoned
   * attempt never leaves the resource inconsistent. Returns the staging id of the remux. Runs in the command consumer,
   * where the resource's commands are serialized.
   */
  override def remuxForStreaming(info: ResourceInfo): EitherT[IO, StreamabilityError, Option[StagingId]] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(message => StreamabilityError.FixFailed(info.path, message))
      operation <-
        EitherT.fromOption[IO](AddFastStart.forResource(info), StreamabilityError.UnsupportedContainer(info.contentType.getOrElse("unknown")))
      result    <- EitherT {
                     // A unique name keeps a lease-stolen attempt from writing the same file as the original one.
                     val stagingId = StagingId(s"${info.resourceId}-normalize-${UUID.randomUUID()}.${operation.container.extension}")
                     val temp      = config.tempPath.resolve(stagingId)
                     val remux     =
                       for
                         _          <- IO.blocking(Files.createDirectories(config.tempPath))
                         out        <- addFastStart(config.resourcePath.resolve(info.path), operation.container, temp)
                         streamable <- streamableOf(out, info.contentType)
                         result     <-
                           if streamable.contains(true) then IO.pure(Right(Some(stagingId)): Either[StreamabilityError, Option[StagingId]])
                           else
                             IO.blocking(Files.deleteIfExists(temp)).void.as(
                               Left(StreamabilityError.FixFailed(
                                 info.path,
                                 "the remux did not produce a streamable file"
                               )): Either[StreamabilityError, Option[StagingId]]
                             )
                       yield result
                     remux.onError(_ => IO.blocking(Files.deleteIfExists(temp)).void)
                   }
    yield result

  /**
   * Replaces the live file with the remux produced by [[remuxForStreaming]] (when one is still present) and refreshes the
   * metadata that depends on the file. Idempotent: a retry after the move finds no staging file and re-derives the metadata.
   */
  override def commitStreamability(info: ResourceInfo, stagingId: Option[StagingId]): EitherT[IO, StreamabilityError, ResourceInfo] =
    val source = config.resourcePath.resolve(info.path)
    for
      _           <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(message => StreamabilityError.FixFailed(info.path, message))
      _           <- stagingId match
                       case Some(id) =>
                         val temp = config.tempPath.resolve(id)
                         EitherT.liftF(
                           for
                             exists <- IO.blocking(Files.exists(temp))
                             _      <- if exists then moveIntoPlace(temp, source).void else IO.unit
                           yield ()
                         )
                       case None     => EitherT.pure[IO, StreamabilityError](())
      attrs       <- EitherT.liftF(IO.blocking(Files.readAttributes(source, classOf[BasicFileAttributes])))
      partialHash <- EitherT.liftF(config.hashingAlgorithm.createHash(source))
      streamable  <- EitherT.liftF(streamableOf(source, info.contentType))
      updated     <- EitherT.cond[IO](
                       streamable.contains(true),
                       info.copy(
                         size             = attrs.size(),
                         partialHash      = partialHash,
                         fileKey          = Option(attrs.fileKey()).map(_.toString),
                         timeLastModified = Some(attrs.lastModifiedTime().toMillis),
                         contentMeta      = info.contentMeta.copy(streamable = streamable)
                       ),
                       StreamabilityError.FixFailed(info.path, "the file is not streamable after the remux")
                     )
    yield updated

  /**
   * Validator for a derived preview. It is derived from the source's partial hash, the operation (encoded in the output
   * file name) and the configured encoder options, so it never requires reading or hashing the produced bytes.
   *
   * Known gap: because it describes the recipe rather than the produced bytes, it does not change when the same recipe
   * starts yielding different output (for example after a toolchain upgrade). Bump [[ResourceOperations.previewRecipeVersion]]
   * to invalidate in that case.
   */
  private def previewETag(info: ResourceInfo, outputFile: Path): String =
    ETag.of(info.partialHash, outputFile.getFileName.toString, formats.settingsFingerprint, ResourceOperations.previewRecipeVersion.toString)

  override def derivedResource(info: ResourceInfo, operation: ResourceOperation): EitherT[IO, DerivedResourceError, ResourceContent] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(reason => DerivedResourceError.InvalidPath(info.resourceId, reason))
      source     = config.resourcePath.resolve(info.path)
      _         <- EitherT.cond[IO](Files.exists(source), (), DerivedResourceError.SourceMissing(info.resourceId, source.toString))
      outputFile = operation.outputFile(info.resourceId, ResourceOperations.contentVersion(info))
      eTag       = Some(previewETag(info, outputFile))
      key        = (resourceId = info.resourceId, operation = operation)
      content   <- EitherT.liftF(
                     if Files.exists(outputFile) then IO.pure(Streamed(StreamedResource.fromPath(outputFile, Some(key.operation.contentType), eTag)))
                     else
                       inFlight
                         .run(key)(createResource(source, info, key.operation).as(Option.empty[ResourceInfo]))
                         .map(_ => Streamed(StreamedResource.fromPath(outputFile, Some(key.operation.contentType), eTag)))
                   )
    yield content

  private def createResource(inputFile: Path, info: ResourceInfo, operation: ResourceOperation): IO[Path] =
    for
      _         <- IO.fromEither(operation.validate(info).left.map(new Exception(_)))
      outputFile = operation.outputFile(info.resourceId, ResourceOperations.contentVersion(info))
      tempFile   = temporaryOutputFile(outputFile)
      create     = IO.blocking(Files.createDirectories(outputFile.getParent)) >>
                     run(info, inputFile, tempFile, operation) >>
                     moveIntoPlace(tempFile, outputFile)
      path      <- create
                     .onError(_ => IO.blocking(Files.deleteIfExists(tempFile)).void)
                     .onCancel(IO.blocking(Files.deleteIfExists(tempFile)).void)
                     .memoize
                     .flatten
    yield path

  private def temporaryOutputFile(outputFile: Path): Path = {
    val fileName              = outputFile.getFileName.toString
    val extensionStart        = fileName.lastIndexOf('.')
    val (baseName, extension) = if extensionStart >= 0 then fileName.splitAt(extensionStart) else (fileName, "")
    outputFile.resolveSibling(s".$baseName.${UUID.randomUUID()}$extension")
  }

  private def moveIntoPlace(tempFile: Path, outputFile: Path): IO[Path] =
    IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
      .recoverWith {
        case _: AtomicMoveNotSupportedException =>
          IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING))
      }
      .as(outputFile)

  private def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
    case VideoFragment(width, height, start, end) =>
      logger.debug(s"Creating video fragment for $inputFile with range $start-$end")
      FFMpeg.transcodeToMp4(inputFile.toUri, range = (start, end), scaleWidth = width, scaleHeight = height, outputFile = outputFile).map(_ =>
        outputFile
      )

    case VideoThumbnail(width, height, timestamp, format) =>
      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp as ${format.configName}")
      FFMpeg.createThumbnail(
        inputFile.toUri,
        timestamp   = timestamp,
        outputFile  = outputFile,
        scaleWidth  = width,
        scaleHeight = height,
        codecArgs   = formats.encoderArgs(format)
      ).map(_ =>
        outputFile
      )
    case ImageThumbnail(width, height, format)            =>
      logger.debug(s"Creating image thumbnail for $inputFile as ${format.configName}")
      FFMpeg.resizeImage(
        inputFile.toUri,
        outputFile = outputFile,
        width      = width,
        height     = height,
        codecArgs  = formats.encoderArgs(format)
      ).map(_ => outputFile)

    case tile: VideoThumbnailTile =>
      createThumbnailTileSprite(info, inputFile, outputFile, tile).map(_ => outputFile)

    case tile: VideoThumbnailTileVtt =>
      createThumbnailTileManifest(info, outputFile, tile).map(_ => outputFile)

    case AddFastStart(_) =>
      IO.raiseError(new IllegalArgumentException("AddFastStart updates a file in place and produces no derived output"))

  private def videoProperties(info: ResourceInfo): VideoProperties =
    info.basicContentProperties
      .collect { case video: VideoProperties => video }
      .getOrElse(throw new IllegalStateException(s"Resource '${info.resourceId}' has no video properties"))

  private def createThumbnailTileSprite(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: VideoThumbnailTile): IO[Unit] = {
    val video           = videoProperties(info)
    val duration        = video.durationInMillis.toLong
    val (width, height) = VideoThumbnailTile.resolutionFor(operation.spritePixels, video.width, video.height)
    val frameCount      = VideoThumbnailTile.frameCountInSprite(operation.index, duration, operation.intervalInMillis)
    val start           = VideoThumbnailTile.startInMillis(operation.index, operation.intervalInMillis)
    val plan            = ThumbnailTile(
      width,
      height,
      VideoThumbnailTile.columnsPerSprite,
      frameCount,
      operation.intervalInMillis,
      start,
      VideoThumbnailTile.spriteQuality
    )

    logger.debug(s"Creating thumbnail tile ${operation.index} for $inputFile at ${operation.intervalInMillis}ms interval")
    FFMpeg.createThumbnailTile(inputFile.toUri, outputFile, plan)
  }

  private def createThumbnailTileManifest(info: ResourceInfo, outputFile: Path, operation: VideoThumbnailTileVtt): IO[Unit] = {
    val video           = videoProperties(info)
    val contentVersion  = ResourceOperations.contentVersion(info)
    val (width, height) = VideoThumbnailTile.resolutionFor(operation.spritePixels, video.width, video.height)
    val spriteUrl       = (index: Int) => s"/api/resources/${info.bucketId}/${info.resourceId}/thumbtile_${contentVersion}_${operation.token}_$index.webp"

    logger.debug(s"Creating thumbnail tile manifest for '${info.path}' at ${operation.intervalInMillis}ms interval")
    val webVtt = VideoThumbnailTile.renderWebVtt(
      durationInMillis = video.durationInMillis.toLong,
      intervalInMillis = operation.intervalInMillis,
      width            = width,
      height           = height,
      spriteUrl        = spriteUrl
    )

    IO.blocking(Files.write(outputFile, webVtt.getBytes(StandardCharsets.UTF_8))).void
  }

  private def previewOperations(info: ResourceInfo): List[ResourceOperation] =
    ResourceOperations.all(info, resolutions, formats).filter(_.validate(info).isRight)

  private def runPreviewOperation(info: ResourceInfo, operation: ResourceOperation): IO[Unit] =
    derivedResource(info, operation)
      .foldF(
        e => IO(logger.warn(s"Skipping preview $operation for '${info.path}': ${e.message}")),
        _ => IO.unit
      )
      .attempt
      .flatMap {
        case Left(error) => IO(logger.error(s"Failed to generate preview $operation for '${info.path}'", error))
        case Right(_)    => IO.unit
      }

  override def generatePreviews(info: ResourceInfo): IO[Unit] =
    fs2.Stream.emits(previewOperations(info))
      .parEvalMap(parallelFactor)(runPreviewOperation(info, _))
      .compile.drain
}
