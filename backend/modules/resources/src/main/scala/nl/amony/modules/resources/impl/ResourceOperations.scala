package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, NoSuchFileException, Path, StandardCopyOption}
import java.util.UUID
import scala.util.{Failure, Success, Using}

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.implicits.*
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}
import org.apache.tika.Tika
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.util.{ETag, PartialHash, SingleFlight}
import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile
import nl.amony.platform.subprocess.ffmpeg.{ContainerFormat, FFMpeg}
import nl.amony.platform.subprocess.magick.ImageMagick
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessRunner, ProcessSettings}

/** Where content to operate on lives: a resource in the bucket or a local scratch file. */
enum ContentLocation:
  case Resource(path: ResourcePath)
  case LocalFile(path: Path)

  def display: String = this match
    case Resource(path)  => path
    case LocalFile(path) => path.getFileName.toString

/** Result of remuxing a resource for progressive streaming: its target path and updated partial hash. */
final case class RemuxResult(targetPath: ResourcePath, partialHash: String)

/** Operations on the content of a bucket. */
trait ResourceOperations:

  def contentMeta(location: ContentLocation): EitherT[IO, ContentUnreadable, ContentMeta]

  def contentPartialHash(location: ContentLocation): EitherT[IO, ContentUnreadable, String]

  def isStreamable(location: ContentLocation, contentType: Option[String]): EitherT[IO, ContentUnreadable, Option[Boolean]]

  def normalizeUpload(file: Path, container: VideoContainer): EitherT[IO, StreamabilityError, Unit]

  def remuxForStreaming(info: ResourceInfo, targetPath: ResourcePath): EitherT[IO, StreamabilityError, Option[RemuxResult]]

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
  parallelFactor: Int,
  formats: ThumbnailFormats,
  resolutions: ThumbnailResolutions,
  processSettings: ProcessSettings,
  processLimiter: ProcessLimiter,
  store: ResourceStore
)(using meter: Meter[IO], tracer: Tracer[IO]) extends ResourceOperations, Logging {

  private given runner: ProcessRunner = new ProcessRunner(processSettings, processLimiter)
  private val tika                    = new Tika()

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  private val inFlight = SingleFlight[OperationKey, Unit]

  private def tempFile(prefix: String, suffix: String = ""): Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempFile(prefix, suffix)))(path => IO.blocking(Files.deleteIfExists(path)).void)

  private def mediaSourceOf(location: ContentLocation): EitherT[IO, ContentUnreadable, MediaSource] = location match
    case ContentLocation.Resource(path)  =>
      for
        _      <- EitherT.fromEither[IO](
                    ResourcePath.fromString(path).left.map(message => ContentUnreadable(location, new IllegalArgumentException(message)))
                  )
        source <- EitherT.fromOptionF(
                    store.openSource(path),
                    ContentUnreadable(location, new NoSuchFileException(path))
                  )
      yield source
    case ContentLocation.LocalFile(path) => EitherT.pure[IO, ContentUnreadable](MediaSource.LocalFile(path))

  private def localPathOf(location: ContentLocation): EitherT[IO, ContentUnreadable, Path] =
    mediaSourceOf(location).flatMap {
      case MediaSource.LocalFile(p) => EitherT.pure[IO, ContentUnreadable](p)
      case MediaSource.RemoteUrl(u) =>
        EitherT.leftT[IO, Path](
          ContentUnreadable(location, new UnsupportedOperationException(s"Partial hashing is not supported for remote media sources: $u"))
        )
    }

  private def contentTypeFor(location: ContentLocation, source: MediaSource): EitherT[IO, ContentUnreadable, String] =
    val detect = source match
      case MediaSource.LocalFile(path) => IO.blocking(tika.detect(path))
      case MediaSource.RemoteUrl(uri)  =>
        IO.blocking(Using.resource(uri.toURL.openStream())(stream => tika.detect(stream, location.display)))
    EitherT(detect.attempt.map(_.left.map(ContentUnreadable(location, _))))

  override def contentMeta(location: ContentLocation): EitherT[IO, ContentUnreadable, ContentMeta] =
    for
      source      <- mediaSourceOf(location)
      contentType <- contentTypeFor(location, source)
      scan        <- EitherT.liftF(toolScanForSource(source, contentType))
      streamable  <- EitherT.liftF(streamableOf(source, Some(contentType)))
    yield scan.map(ContentMeta.scanned).getOrElse(ContentMeta.empty)
      .copy(contentType = Some(contentType), streamable = streamable)

  override def contentPartialHash(location: ContentLocation): EitherT[IO, ContentUnreadable, String] =
    for
      path <- localPathOf(location)
      hash <- EitherT(PartialHash.createHash(path).attempt.map(_.left.map(ContentUnreadable(location, _))))
    yield hash

  override def isStreamable(location: ContentLocation, contentType: Option[String]): EitherT[IO, ContentUnreadable, Option[Boolean]] =
    for
      source     <- mediaSourceOf(location)
      streamable <- EitherT.liftF(streamableOf(source, contentType))
    yield streamable

  private def toolScanForSource(source: MediaSource, contentType: String): IO[Option[ToolScan]] =
    if contentType.startsWith("video/") then
      FFMpeg.ffprobe(source.toUri, processSettings.ffprobeTimeout).map {
        (ffprobeResult, json) =>
          val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
          Some(ToolScan(s"ffprobe/$version", json))
      }.recover { case e: Throwable => logger.error(s"Failed to get video meta data for ${source.toUri}", e); None }
    else if contentType.startsWith("image/") then
      ImageMagick.getImageMeta(source.toUri).map:
        case Failure(e)      =>
          logger.error(s"Failed to get image meta data for ${source.toUri}", e)
          None
        case Success(result) =>
          Some(ToolScan("magick/1", result.rawJson))
    else IO.pure(None)

  private def addFastStart(source: MediaSource, container: VideoContainer, outputFile: Path): IO[Path] =
    FFMpeg.addFastStart(source.toUri, containerFormat(container), outputFile)

  override def normalizeUpload(file: Path, container: VideoContainer): EitherT[IO, StreamabilityError, Unit] =
    EitherT(
      tempFile("amony-normalize-", s".${container.extension}").use { normalized =>
        for
          out <- addFastStart(MediaSource.LocalFile(file), container, normalized)
          _   <- moveIntoPlace(out, file)
        yield ()
      }.attempt.map {
        case Right(())   => Right(())
        case Left(error) =>
          Left(StreamabilityError.FixFailed(file.getFileName.toString, s"Failed to normalize the uploaded file: ${error.getMessage}"))
      }
    )

  private def containerFormat(container: VideoContainer): ContainerFormat = container match
    case VideoContainer.Mp4      => ContainerFormat.Mp4
    case VideoContainer.Webm     => ContainerFormat.Webm
    case VideoContainer.Matroska => ContainerFormat.Matroska

  private[impl] def streamableOf(source: MediaSource, contentType: Option[String]): IO[Option[Boolean]] =
    if contentType.exists(_.startsWith("video/")) then
      Streamability.detect(source.toUri).map {
        case Streamability.Streamable    => Some(true)
        case Streamability.NotStreamable => Some(false)
        case Streamability.Unknown       => None
      }
    else IO.pure(None)

  override def remuxForStreaming(info: ResourceInfo, targetPath: ResourcePath): EitherT[IO, StreamabilityError, Option[RemuxResult]] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(message => StreamabilityError.FixFailed(info.path, message))
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(targetPath)).leftMap(message => StreamabilityError.FixFailed(info.path, message))
      operation <-
        EitherT.fromOption[IO](AddFastStart.forResource(info), StreamabilityError.UnsupportedContainer(info.contentType.getOrElse("unknown")))
      source    <- EitherT.fromOptionF(
                     store.openSource(info.path),
                     StreamabilityError.FixFailed(info.path, "source file is no longer available")
                   )
      result    <- EitherT {
                     tempFile("amony-remux-", s".${operation.container.extension}").use { temp =>
                       for
                         out        <- addFastStart(source, operation.container, temp)
                         streamable <- streamableOf(MediaSource.LocalFile(out), info.contentType)
                         result     <-
                           if streamable.contains(true) then
                             for
                               partialHash <- PartialHash.createHash(out)
                               putResult   <- store
                                                .put(StoreKey.Resource(targetPath), info.contentType, Fs2Files[IO].readAll(Fs2Path.fromNioPath(out)))
                                                .leftMap(message => StreamabilityError.FixFailed(info.path, message))
                                                .as(Some(RemuxResult(targetPath, partialHash)))
                                                .value
                             yield putResult
                           else
                             IO.pure(
                               Left(StreamabilityError.FixFailed(
                                 info.path,
                                 "the remux did not produce a streamable file"
                               )): Either[StreamabilityError, Option[RemuxResult]]
                             )
                       yield result
                     }.onError(_ => IO.whenA(targetPath != info.path)(store.delete(StoreKey.Resource(targetPath))))
                   }
    yield result

  private def previewETag(info: ResourceInfo, key: StoreKey.Derived): String =
    ETag.of(info.partialHash, key.name, formats.settingsFingerprint, ResourceOperation.previewRecipeVersion.toString)

  override def derivedResource(info: ResourceInfo, operation: ResourceOperation): EitherT[IO, DerivedResourceError, ResourceContent] =
    for
      _       <- EitherT.fromEither[IO](ResourcePath.fromString(info.path)).leftMap(reason => DerivedResourceError.InvalidPath(info.resourceId, reason))
      key      = StoreKey.derived(info.resourceId, ResourceOperation.contentVersion(info), operation)
      eTag     = Some(previewETag(info, key))
      opKey    = (resourceId = info.resourceId, operation = operation)
      content <- EitherT(
                   store.content(key, Some(operation.contentType), eTag).flatMap {
                     case Some(existing) => IO.pure(Right(existing))
                     case None           =>
                       store.openSource(info.path).flatMap {
                         case None         => IO.pure(Left(DerivedResourceError.SourceMissing(info.resourceId, info.path)))
                         case Some(source) =>
                           inFlight.run(opKey)(createDerived(source, info, key, operation)) >>
                             store.content(key, Some(operation.contentType), eTag).flatMap {
                               case Some(created) => IO.pure(Right(created))
                               case None          =>
                                 IO.raiseError(new IllegalStateException(s"Derived resource '${key.name}' was not found after creation"))
                             }
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
        tempFile("amony-derived-", suffix).use { tempFile =>
          run(info, source, tempFile, other) >>
            store
              .put(key, Some(other.contentType), Fs2Files[IO].readAll(Fs2Path.fromNioPath(tempFile)))
              .value
              .flatMap(e => IO.fromEither(e.left.map(new RuntimeException(_))))
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
    val contentVersion  = ResourceOperation.contentVersion(info)
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
    ResourceOperation.all(info, resolutions, formats).filter(_.validate(info).isRight)

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
