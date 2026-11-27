package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, NoSuchFileException, Path, StandardCopyOption}
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

  def isStreamable(location: ContentLocation, contentType: Option[String]): EitherT[IO, ContentUnreadable, Option[Boolean]]

  def normalizeUpload(stagingId: StagingId, container: VideoContainer): EitherT[IO, StreamabilityError, Unit]

  def remuxForStreaming(info: ResourceInfo): EitherT[IO, StreamabilityError, Option[StagingId]]

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
  hashingAlgorithm: HashingAlgorithm,
  parallelFactor: Int,
  formats: ThumbnailFormats,
  resolutions: ThumbnailResolutions,
  processSettings: ProcessSettings,
  processLimiter: ProcessLimiter,
  store: ResourceStore,
  staging: StagingArea
)(using meter: Meter[IO], tracer: Tracer[IO]) extends BucketOperations, Logging {

  private given runner: ProcessRunner = new ProcessRunner(processSettings, processLimiter)
  private val tika                    = new Tika()

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  private val inFlight = SingleFlight[OperationKey, Unit]

  private def localPathOf(location: ContentLocation): EitherT[IO, ContentUnreadable, Path] = location match
    case ContentLocation.Resource(path)    =>
      for
        _      <- EitherT.fromEither[IO](
                    ResourcePath.fromString(path).left.map(message => ContentUnreadable(location, new IllegalArgumentException(message)))
                  )
        source <- EitherT.fromOptionF(
                    store.openSource(path),
                    ContentUnreadable(location, new NoSuchFileException(path))
                  )
        local  <- source match
                    case MediaSource.LocalFile(p) => EitherT.pure[IO, ContentUnreadable](p)
                    case MediaSource.RemoteUrl(u) =>
                      EitherT.leftT[IO, Path](ContentUnreadable(
                        location,
                        new UnsupportedOperationException(s"Remote media source is not supported: $u")
                      ))
      yield local
    case ContentLocation.Staged(stagingId) =>
      EitherT.fromOptionF(
        staging.get(stagingId).map(_.map(_.path)),
        ContentUnreadable(location, new NoSuchFileException(stagingId))
      )

  private def contentTypeFor(location: ContentLocation, path: Path): EitherT[IO, ContentUnreadable, String] =
    EitherT(IO.blocking(tika.detect(path)).attempt.map(_.left.map(ContentUnreadable(location, _))))

  override def contentMeta(location: ContentLocation): EitherT[IO, ContentUnreadable, ContentMeta] =
    for
      path        <- localPathOf(location)
      contentType <- contentTypeFor(location, path)
      scan        <- EitherT.liftF(toolScanForPath(path, contentType))
      streamable  <- EitherT.liftF(streamableOf(path, Some(contentType)))
    yield scan.map(ContentMeta.scanned).getOrElse(ContentMeta.empty)
      .copy(contentType = Some(contentType), streamable = streamable)

  override def contentPartialHash(location: ContentLocation): EitherT[IO, ContentUnreadable, String] =
    for
      path <- localPathOf(location)
      hash <- EitherT(hashingAlgorithm.createHash(path).attempt.map(_.left.map(ContentUnreadable(location, _))))
    yield hash

  override def isStreamable(location: ContentLocation, contentType: Option[String]): EitherT[IO, ContentUnreadable, Option[Boolean]] =
    for
      path       <- localPathOf(location)
      streamable <- EitherT.liftF(streamableOf(path, contentType))
    yield streamable

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

  private def addFastStart(source: MediaSource, container: VideoContainer, outputFile: Path): IO[Path] =
    FFMpeg.addFastStart(source.toUri, containerFormat(container), outputFile)

  override def normalizeUpload(stagingId: StagingId, container: VideoContainer): EitherT[IO, StreamabilityError, Unit] =
    EitherT(
      staging.get(stagingId).flatMap {
        case None         =>
          IO.pure(Left(StreamabilityError.FixFailed(stagingId, "the staged upload is no longer available")))
        case Some(staged) =>
          staging.tempFile(s"$stagingId-normalized").use { normalized =>
            for
              out <- addFastStart(MediaSource.LocalFile(staged.path), container, normalized)
              _   <- moveIntoPlace(out, staged.path)
            yield ()
          }.attempt.map {
            case Right(())   => Right(())
            case Left(error) => Left(StreamabilityError.FixFailed(stagingId, s"Failed to normalize the staged upload: ${error.getMessage}"))
          }
      }
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

  override def remuxForStreaming(info: ResourceInfo): EitherT[IO, StreamabilityError, Option[StagingId]] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(message => StreamabilityError.FixFailed(info.path, message))
      operation <-
        EitherT.fromOption[IO](AddFastStart.forResource(info), StreamabilityError.UnsupportedContainer(info.contentType.getOrElse("unknown")))
      source    <- EitherT.fromOptionF(
                     store.openSource(info.path),
                     StreamabilityError.FixFailed(info.path, "source file is no longer available")
                   )
      result    <- EitherT {
                     val stagingId = StagingId(s"${info.resourceId}-normalize-${UUID.randomUUID()}.${operation.container.extension}")
                     val remux     =
                       for
                         temp       <- staging.prepare(stagingId)
                         out        <- addFastStart(source, operation.container, temp)
                         streamable <- streamableOf(out, info.contentType)
                         result     <-
                           if streamable.contains(true) then IO.pure(Right(Some(stagingId)): Either[StreamabilityError, Option[StagingId]])
                           else
                             staging.delete(stagingId).as(
                               Left(StreamabilityError.FixFailed(
                                 info.path,
                                 "the remux did not produce a streamable file"
                               )): Either[StreamabilityError, Option[StagingId]]
                             )
                       yield result
                     remux.onError(_ => staging.delete(stagingId))
                   }
    yield result

  private def previewETag(info: ResourceInfo, key: StoreKey.Derived): String =
    ETag.of(info.partialHash, key.name, formats.settingsFingerprint, ResourceOperations.previewRecipeVersion.toString)

  override def derivedResource(info: ResourceInfo, operation: ResourceOperation): EitherT[IO, DerivedResourceError, ResourceContent] =
    for
      _       <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(reason => DerivedResourceError.InvalidPath(info.resourceId, reason))
      source  <- EitherT.fromOptionF(
                   store.openSource(info.path),
                   DerivedResourceError.SourceMissing(info.resourceId, info.path)
                 )
      key      = StoreKey.derived(info.resourceId, ResourceOperations.contentVersion(info), operation)
      eTag     = Some(previewETag(info, key))
      opKey    = (resourceId = info.resourceId, operation = operation)
      content <- EitherT.liftF(
                   store.content(key, Some(operation.contentType), eTag).flatMap {
                     case Some(existing) => IO.pure(existing)
                     case None           =>
                       inFlight.run(opKey)(createDerived(source, info, key, operation)) >>
                         store.content(key, Some(operation.contentType), eTag).flatMap {
                           case Some(created) => IO.pure(created)
                           case None          =>
                             IO.raiseError(new IllegalStateException(s"Derived resource '${key.name}' was not found after creation"))
                         }
                   }
                 )
    yield content

  private def createDerived(source: MediaSource, info: ResourceInfo, key: StoreKey.Derived, operation: ResourceOperation): IO[Unit] =
    IO.fromEither(operation.validate(info).left.map(new Exception(_))) >> (operation match
      case tile: VideoThumbnailTileVtt =>
        val webVtt = renderThumbnailTileManifest(info, tile)
        store.putBytes(key, Some(operation.contentType), webVtt.getBytes(StandardCharsets.UTF_8))

      case other =>
        val extensionStart = key.name.lastIndexOf('.')
        val suffix         = if extensionStart >= 0 then key.name.substring(extensionStart) else ""
        staging.tempFile(key.name, suffix).use { tempFile =>
          run(info, source, tempFile, other) >>
            store.put(key, Some(other.contentType), tempFile).value.flatMap(e => IO.fromEither(e.left.map(new RuntimeException(_)))).void
        })

  private def moveIntoPlace(tempFile: Path, outputFile: Path): IO[Path] =
    IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
      .recoverWith {
        case _: AtomicMoveNotSupportedException =>
          IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING))
      }
      .as(outputFile)

  private def run(info: ResourceInfo, source: MediaSource, outputFile: Path, operation: ResourceOperation): IO[Unit] = operation match
    case VideoFragment(width, height, start, end) =>
      logger.debug(s"Creating video fragment for ${source.toUri} with range $start-$end")
      FFMpeg.transcodeToMp4(source.toUri, range = (start, end), scaleWidth = width, scaleHeight = height, outputFile = outputFile).void

    case VideoThumbnail(width, height, timestamp, format) =>
      logger.debug(s"Creating thumbnail for ${source.toUri} at timestamp $timestamp as ${format.configName}")
      FFMpeg.createThumbnail(
        source.toUri,
        timestamp   = timestamp,
        outputFile  = outputFile,
        scaleWidth  = width,
        scaleHeight = height,
        codecArgs   = formats.encoderArgs(format)
      ).void

    case ImageThumbnail(width, height, format) =>
      logger.debug(s"Creating image thumbnail for ${source.toUri} as ${format.configName}")
      FFMpeg.resizeImage(
        source.toUri,
        outputFile = outputFile,
        width      = width,
        height     = height,
        codecArgs  = formats.encoderArgs(format)
      ).void

    case tile: VideoThumbnailTile =>
      createThumbnailTileSprite(info, source, outputFile, tile)

    case _: VideoThumbnailTileVtt =>
      IO.raiseError(new IllegalArgumentException("VideoThumbnailTileVtt is rendered directly without media processing"))

    case AddFastStart(_) =>
      IO.raiseError(new IllegalArgumentException("AddFastStart updates a file in place and produces no derived output"))

  private def videoProperties(info: ResourceInfo): VideoProperties =
    info.basicContentProperties
      .collect { case video: VideoProperties => video }
      .getOrElse(throw new IllegalStateException(s"Resource '${info.resourceId}' has no video properties"))

  private def createThumbnailTileSprite(info: ResourceInfo, source: MediaSource, outputFile: Path, operation: VideoThumbnailTile): IO[Unit] = {
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

    logger.debug(s"Creating thumbnail tile ${operation.index} for ${source.toUri} at ${operation.intervalInMillis}ms interval")
    FFMpeg.createThumbnailTile(source.toUri, outputFile, plan)
  }

  private def renderThumbnailTileManifest(info: ResourceInfo, operation: VideoThumbnailTileVtt): String = {
    val video           = videoProperties(info)
    val contentVersion  = ResourceOperations.contentVersion(info)
    val (width, height) = VideoThumbnailTile.resolutionFor(operation.spritePixels, video.width, video.height)
    val spriteUrl       = (index: Int) => s"/api/resources/${info.bucketId}/${info.resourceId}/thumbtile_${contentVersion}_${operation.token}_$index.webp"

    logger.debug(s"Creating thumbnail tile manifest for '${info.path}' at ${operation.intervalInMillis}ms interval")
    VideoThumbnailTile.renderWebVtt(
      durationInMillis = video.durationInMillis.toLong,
      intervalInMillis = operation.intervalInMillis,
      width            = width,
      height           = height,
      spriteUrl        = spriteUrl
    )
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
