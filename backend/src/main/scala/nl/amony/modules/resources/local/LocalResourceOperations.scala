package nl.amony.modules.resources.local

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import cats.effect.std.MapRef
import cats.effect.{Deferred, IO}
import cats.implicits.*
import scribe.Logging

import nl.amony.lib.hash.Base32
import nl.amony.modules.resources.api.*

trait LocalResourceOperations extends LocalDirectoryBase with Logging {

  self: ResourceBucket =>

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

  private val mapRefOps: MapRef[IO, OperationKey, Option[Deferred[IO, Either[Throwable, Path]]]] =
    MapRef.fromConcurrentHashMap[IO, OperationKey, Deferred[IO, Either[Throwable, Path]]](
      new ConcurrentHashMap[OperationKey, Deferred[IO, Either[Throwable, Path]]]()
    )

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
    else {

      def runOperation(deferred: Deferred[IO, Either[Throwable, Path]]): IO[Path] =
        createResource(config.resourcePath.resolve(info.path), info, key.operation)
          .attempt
          .flatTap(result => deferred.complete(result))
          .flatTap(_ => mapRefOps(key).set(None))
          .rethrow

      Deferred[IO, Either[Throwable, Path]].flatMap {
        newDeferred =>
          mapRefOps(key).modify {
            case Some(existing) => (Some(existing), existing.get.rethrow)
            case None           => (Some(newDeferred), runOperation(newDeferred))
          }
      }.flatten.map(_ => ResourceContent.fromPath(outputFile, Some(key.operation.contentType), eTag).some)
    }
  }

  private def createResource(inputFile: Path, info: ResourceInfo, operation: ResourceOperation): IO[Path] =
    operation.validate(info) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(_)    =>
        val outputFile = operation.outputFile(info.resourceId, ResourceOperations.contentVersion(info))
        IO.blocking(Files.createDirectories(outputFile.getParent)) >> run(info, inputFile, outputFile, operation).memoize.flatten

  protected def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
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
