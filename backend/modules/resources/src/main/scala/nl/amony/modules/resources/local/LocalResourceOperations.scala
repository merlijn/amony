package nl.amony.modules.resources.local

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.util.{Failure, Success, Try}

import cats.effect.IO
import cats.implicits.*
import org.apache.tika.Tika
import scribe.Logging

import nl.amony.modules.resources.api.*
import nl.amony.platform.concurrent.SingleFlight
import nl.amony.platform.hash.Base32
import nl.amony.platform.subprocess.ffmpeg.ContainerFormat
import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.magick.ImageMagick

trait LocalResourceOperations extends LocalDirectoryBase with Logging {

  self: ResourceBucket =>

  private val ffmpeg      = new FFMpeg
  private val imageMagick = new ImageMagick
  private val tika        = new Tika()

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  extension (operation: ResourceOperation)
    def outputFile(resourceId: ResourceId, contentVersion: String): Path = {

      // Encode the pinned dimension so width- and height-based outputs never collide in the cache.
      def scaleSuffix(width: Option[Int], height: Option[Int]): String =
        width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

      val fileName = operation match
        case VideoFragment(width, height, start, end)         => s"${resourceId}_${contentVersion}_$start-${end}_${scaleSuffix(width, height)}.mp4"
        case VideoThumbnail(width, height, timestamp, format) =>
          s"${resourceId}_${contentVersion}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
        case ImageThumbnail(width, height, format)            => s"${resourceId}_${contentVersion}_${scaleSuffix(width, height)}.${format.extension}"

      config.cachePath.resolve(fileName)
    }

  private val inFlight = SingleFlight[OperationKey, Path]

  private[local] def contentTypeForPath(path: Path): IO[Option[String]] =
    IO.blocking {
      Try(tika.detect(path)) match
        case Success(contentType) =>
          Some(contentType)
        case Failure(exception)   =>
          logger.warn(s"Error detecting content type for path: $path", exception)
          None
    }

  private[local] def resourceMetaForPath(path: Path): IO[Option[ResourceMeta]] =
    contentTypeForPath(path).flatMap {
      case None              => IO.pure(None)
      case Some(contentType) =>
        for
          scan       <- toolScanForPath(path, contentType)
          streamable <- streamableOf(path, Some(contentType))
        yield Some(
          scan.map(ResourceMeta.scanned).getOrElse(ResourceMeta.empty)
            .copy(contentType = Some(contentType), streamable = streamable)
        )
    }

  private def toolScanForPath(path: Path, contentType: String): IO[Option[ToolScan]] =
    if contentType.startsWith("video/") then
      ffmpeg.ffprobe(path, false).map {
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

  private[local] def addFastStart(video: Path, container: VideoContainer, outputFile: Option[Path] = None): IO[Path] =
    ffmpeg.addFastStart(video, containerFormat(container), outputFile)

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
        IO.blocking(Files.createDirectories(outputFile.getParent)) >> run(info, inputFile, outputFile, operation).memoize.flatten

  private def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
    case VideoFragment(width, height, start, end) =>
      logger.debug(s"Creating video fragment for $inputFile with range $start-$end")
      ffmpeg.transcodeToMp4(inputFile = inputFile, range = (start, end), scaleWidth = width, scaleHeight = height, outputFile = Some(outputFile)).map(
        _ =>
          outputFile
      )

    case VideoThumbnail(width, height, timestamp, format) =>
      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp as ${format.configName}")
      ffmpeg.createThumbnail(
        inputFile   = inputFile,
        timestamp   = timestamp,
        outputFile  = Some(outputFile),
        scaleWidth  = width,
        scaleHeight = height,
        codecArgs   = formats.encoderArgs(format)
      ).map(_ =>
        outputFile
      )
    case ImageThumbnail(width, height, format)            =>
      logger.debug(s"Creating image thumbnail for $inputFile as ${format.configName}")
      ffmpeg.resizeImage(
        inputFile  = inputFile,
        outputFile = Some(outputFile),
        width      = width,
        height     = height,
        codecArgs  = formats.encoderArgs(format)
      ).map(_ => outputFile)

  private[local] def previewOperations(info: ResourceInfo): List[ResourceOperation] =
    ResourceOperations.all(info, resolutions, formats).filter(_.validate(info).isRight)

  private[local] def runPreviewOperation(info: ResourceInfo, operation: ResourceOperation): IO[Unit] =
    getOrCreate(info.resourceId, operation).attempt.flatMap {
      case Left(error) => IO(logger.error(s"Failed to generate preview $operation for '${info.path}'", error))
      case Right(_)    => IO.unit
    }

  private[local] def generatePreviews(info: ResourceInfo): IO[Unit] =
    fs2.Stream.emits(previewOperations(info))
      .parEvalMap(parallelFactor)(runPreviewOperation(info, _))
      .compile.drain
}
