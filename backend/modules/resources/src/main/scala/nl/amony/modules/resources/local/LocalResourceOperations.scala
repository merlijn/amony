package nl.amony.modules.resources.local

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.UUID
import scala.util.{Failure, Success}

import cats.effect.IO
import cats.implicits.*
import org.apache.tika.Tika
import scribe.Logging

import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.util.Base32
import nl.amony.modules.resources.util.SingleFlight
import nl.amony.platform.subprocess.ffmpeg.ContainerFormat
import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile
import nl.amony.platform.subprocess.magick.ImageMagick

final class ContentUnreadable(val path: Path, cause: Throwable) extends Exception(s"Failed to read content of $path", cause)

trait LocalResourceOperations extends LocalDirectoryBase with Logging {

  private lazy val ffmpeg      = new FFMpeg(processSettings, processLimiter)
  private lazy val imageMagick = new ImageMagick(processSettings, processLimiter)
  private val tika             = new Tika()

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  extension (operation: ResourceOperation)
    def outputFile(resourceId: ResourceId, contentVersion: String): Path = {

      // Encode the pinned dimension so width- and height-based outputs never collide in the cache.
      def scaleSuffix(width: Option[Int], height: Option[Int]): String =
        width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

      val fileName = operation match
        case VideoFragment(width, height, start, end)           => s"${resourceId}_${contentVersion}_$start-${end}_${scaleSuffix(width, height)}.mp4"
        case VideoThumbnail(width, height, timestamp, format)   =>
          s"${resourceId}_${contentVersion}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
        case ImageThumbnail(width, height, format)              => s"${resourceId}_${contentVersion}_${scaleSuffix(width, height)}.${format.extension}"
        case VideoThumbnailTile(width, intervalInMillis, index) =>
          VideoThumbnailTile.spriteFileName(resourceId, contentVersion, width, intervalInMillis, index)
        case VideoThumbnailTileVtt(width, intervalInMillis)     =>
          VideoThumbnailTile.manifestFileName(resourceId, contentVersion, width, intervalInMillis)

      config.cachePath.resolve(fileName)
    }

  private val inFlight = SingleFlight[OperationKey, Path]

  private def contentTypeForPath(path: Path): IO[String] =
    IO.blocking(tika.detect(path)).adaptError { case cause => ContentUnreadable(path, cause) }

  private[local] def contentMetaForPath(path: Path): IO[ContentMeta] =
    contentTypeForPath(path).flatMap { contentType =>
      for
        scan       <- toolScanForPath(path, contentType)
        streamable <- streamableOf(path, Some(contentType))
      yield scan.map(ContentMeta.scanned).getOrElse(ContentMeta.empty)
        .copy(contentType = Some(contentType), streamable = streamable)
    }

  private def toolScanForPath(path: Path, contentType: String): IO[Option[ToolScan]] =
    if contentType.startsWith("video/") then
      ffmpeg.ffprobe(path.toUri, processSettings.ffprobeTimeout).map {
        (ffprobeResult, json) =>
          val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
          Some(ToolScan(s"ffprobe/$version", json))
      }.recover { case e: Throwable => logger.error(s"Failed to get video meta data for $path", e); None }
    else if contentType.startsWith("image/") then
      imageMagick.getImageMeta(path).map:
        case Failure(e)      =>
          logger.error(s"Failed to get image meta data for $path", e)
          None
        case Success(result) =>
          Some(ToolScan("magick/1", result.rawJson))
    else IO.pure(None)

  private[local] def addFastStart(video: Path, container: VideoContainer, outputFile: Option[Path] = None): IO[Path] = {
    // Local files have a well-defined sibling directory, so a default still makes sense here; remote callers must pass an explicit output.
    val out = outputFile.getOrElse(Path.of(s"${FFMpeg.stripExtension(video)}-faststart.${containerFormat(container).extension}"))
    ffmpeg.addFastStart(video.toUri, containerFormat(container), out)
  }

  private def containerFormat(container: VideoContainer): ContainerFormat = container match
    case VideoContainer.Mp4      => ContainerFormat.Mp4
    case VideoContainer.Webm     => ContainerFormat.Webm
    case VideoContainer.Matroska => ContainerFormat.Matroska

  private[local] def streamableOf(path: Path, contentType: Option[String]): IO[Option[Boolean]] =
    if contentType.exists(_.startsWith("video/")) then
      Streamability.detect(path).map {
        case Streamability.Streamable    => Some(true)
        case Streamability.NotStreamable => Some(false)
        case Streamability.Unknown       => None
      }
    else IO.pure(None)

  /**
   * Validator for a derived preview. It is derived from the source's partial hash, the operation (encoded in the output
   * file name) and the configured encoder options, so it never requires reading or hashing the produced bytes.
   *
   * Known gap: because it describes the recipe rather than the produced bytes, it does not change when the same recipe
   * starts yielding different output (for example after a toolchain upgrade). Bump [[ResourceOperations.previewRecipeVersion]]
   * to invalidate in that case.
   */
  private def previewETag(info: ResourceInfo, outputFile: Path): Option[String] =
    info.partialHash.map(sourceHash =>
      eTagOf(sourceHash, outputFile.getFileName.toString, formats.settingsFingerprint, ResourceOperations.previewRecipeVersion.toString)
    )

  /** Validator for a resource's original content, derived from its partial hash and last modified time. */
  private[local] def originalETag(info: ResourceInfo): Option[String] =
    info.partialHash.map(sourceHash => eTagOf(sourceHash, info.timeLastModified.getOrElse(0L).toString))

  private def eTagOf(parts: String*): String =
    Base32.encode(MessageDigest.getInstance("SHA-1").digest(parts.mkString("\u0000").getBytes(StandardCharsets.UTF_8)))

  private[local] def derivedResource(info: ResourceInfo, operation: ResourceOperation): IO[Option[ResourceContent]] = {

    val outputFile = operation.outputFile(info.resourceId, ResourceOperations.contentVersion(info))
    val eTag       = previewETag(info, outputFile)
    val key        = (resourceId = info.resourceId, operation = operation)

    if Files.exists(outputFile) then IO.pure(ResourceContent.fromPath(outputFile, Some(key.operation.contentType), eTag).some)
    else
      inFlight
        .run(key)(createResource(config.resourcePath.resolve(info.path), info, key.operation))
        .map(_ => ResourceContent.fromPath(outputFile, Some(key.operation.contentType), eTag).some)
  }

  private def createResource(inputFile: Path, info: ResourceInfo, operation: ResourceOperation): IO[Path] =
    operation.validate(info) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(_)    =>
        val outputFile = operation.outputFile(info.resourceId, ResourceOperations.contentVersion(info))
        val tempFile   = temporaryOutputFile(outputFile)

        val create = IO.blocking(Files.createDirectories(outputFile.getParent)) >>
          run(info, inputFile, tempFile, operation) >>
          moveIntoPlace(tempFile, outputFile)

        create
          .onError(_ => IO.blocking(Files.deleteIfExists(tempFile)).void)
          .onCancel(IO.blocking(Files.deleteIfExists(tempFile)).void)
          .memoize
          .flatten

  private def temporaryOutputFile(outputFile: Path): Path = {
    val fileName              = outputFile.getFileName.toString
    val extensionStart        = fileName.lastIndexOf('.')
    val (baseName, extension) = if extensionStart >= 0 then fileName.splitAt(extensionStart) else (fileName, "")
    outputFile.resolveSibling(s".$baseName.${UUID.randomUUID()}$extension")
  }

  private def moveIntoPlace(tempFile: Path, outputFile: Path): IO[Path] =
    IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE))
      .recoverWith {
        case _: AtomicMoveNotSupportedException =>
          IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING))
      }
      .as(outputFile)

  private def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
    case VideoFragment(width, height, start, end) =>
      logger.debug(s"Creating video fragment for $inputFile with range $start-$end")
      ffmpeg.transcodeToMp4(inputFile.toUri, range = (start, end), scaleWidth = width, scaleHeight = height, outputFile = outputFile).map(_ =>
        outputFile
      )

    case VideoThumbnail(width, height, timestamp, format) =>
      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp as ${format.configName}")
      ffmpeg.createThumbnail(
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
      ffmpeg.resizeImage(
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

  private def videoProperties(info: ResourceInfo): VideoProperties =
    info.basicContentProperties
      .collect { case video: VideoProperties => video }
      .getOrElse(throw new IllegalStateException(s"Resource '${info.resourceId}' has no video properties"))

  private def createThumbnailTileSprite(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: VideoThumbnailTile): IO[Unit] = {
    val video      = videoProperties(info)
    val duration   = video.durationInMillis.toLong
    val height     = VideoThumbnailTile.heightFor(operation.width, video.width, video.height)
    val frameCount = VideoThumbnailTile.frameCountInSprite(operation.index, duration, operation.intervalInMillis)
    val start      = VideoThumbnailTile.startInMillis(operation.index, operation.intervalInMillis)
    val plan       = ThumbnailTile(operation.width, height, VideoThumbnailTile.gridSize, frameCount, operation.intervalInMillis, start)

    logger.debug(s"Creating thumbnail tile ${operation.index} for $inputFile at ${operation.intervalInMillis}ms interval")
    ffmpeg.createThumbnailTile(inputFile.toUri, outputFile, plan)
  }

  private def createThumbnailTileManifest(info: ResourceInfo, outputFile: Path, operation: VideoThumbnailTileVtt): IO[Unit] = {
    val video          = videoProperties(info)
    val contentVersion = ResourceOperations.contentVersion(info)
    val height         = VideoThumbnailTile.heightFor(operation.width, video.width, video.height)
    val spriteUrl      = (index: Int) => s"/api/resources/${info.bucketId}/${info.resourceId}/thumbtile_${contentVersion}_$index.webp"

    logger.debug(s"Creating thumbnail tile manifest for '${info.path}' at ${operation.intervalInMillis}ms interval")
    val webVtt = VideoThumbnailTile.renderWebVtt(
      durationInMillis = video.durationInMillis.toLong,
      intervalInMillis = operation.intervalInMillis,
      width            = operation.width,
      height           = height,
      spriteUrl        = spriteUrl
    )

    IO.blocking(Files.write(outputFile, webVtt.getBytes(StandardCharsets.UTF_8))).void
  }

  private[local] def previewOperations(info: ResourceInfo): List[ResourceOperation] =
    ResourceOperations.all(info, resolutions, formats).filter(_.validate(info).isRight)

  private[local] def runPreviewOperation(info: ResourceInfo, operation: ResourceOperation): IO[Unit] =
    derivedResource(info, operation).attempt.flatMap {
      case Left(error) => IO(logger.error(s"Failed to generate preview $operation for '${info.path}'", error))
      case Right(_)    => IO.unit
    }

  private[local] def generatePreviews(info: ResourceInfo): IO[Unit] =
    fs2.Stream.emits(previewOperations(info))
      .parEvalMap(parallelFactor)(runPreviewOperation(info, _))
      .compile.drain
}
