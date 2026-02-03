package nl.amony.modules.resources.local

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

import cats.effect.std.MapRef
import cats.effect.{Deferred, IO}
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.resources.api.*

trait LocalResourceOperations extends LocalDirectoryDependencies with Logging {

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  extension (operation: ResourceOperation)
    def outputFilename(resourceId: ResourceId): String = operation match
      case VideoFragment(width, height, start, end, quality) => s"${resourceId}_${height.get}_$start-$end.mp4"
      case VideoThumbnail(width, height, quality, timestamp) => s"${resourceId}_${height.get}_$timestamp.webp"
      case ImageThumbnail(width, height, quality)            => s"${resourceId}_${height.get}.webp"

  private val mapRefOps: MapRef[IO, OperationKey, Option[Deferred[IO, Either[Throwable, Path]]]] =
    MapRef.fromConcurrentHashMap[IO, OperationKey, Deferred[IO, Either[Throwable, Path]]](
      new ConcurrentHashMap[OperationKey, Deferred[IO, Either[Throwable, Path]]]()
    )

  private[local] def derivedResource(info: ResourceInfo, operation: ResourceOperation): IO[Option[ResourceContent]] = {

    val outputFile = config.cachePath.resolve(operation.outputFilename(info.resourceId))
    val key        = (resourceId = info.resourceId, operation = operation)

    if Files.exists(outputFile) then IO.pure(ResourceContent.fromPath(outputFile, Some(key.operation.contentType)).some)
    else {

      def runOperation(deferred: Deferred[IO, Either[Throwable, Path]]): IO[Path] =
        createResource(config.resourcePath.resolve(info.path), info, config.cachePath, key.operation)
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
      }.flatten.map(path => ResourceContent.fromPath(outputFile, Some(key.operation.contentType)).some)
    }
  }

  private def createResource(inputFile: Path, info: ResourceInfo, outputDir: Path, operation: ResourceOperation): IO[Path] =
    operation.validate(info) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(_)    => run(info, inputFile, outputDir.resolve(operation.outputFilename(info.resourceId)), operation).memoize.flatten

  private def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
    case VideoFragment(width, height, start, end, quality) =>
      logger.debug(s"Creating video fragment for $inputFile with range $start-$end")
      // TODO Remove Option.get
      ffmpeg.transcodeToMp4(inputFile = inputFile, range = (start, end), scaleHeight = Some(height.get), outputFile = Some(outputFile)).map(_ =>
        outputFile
      )

    case VideoThumbnail(width, height, quality, timestamp) =>
      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp")
      ffmpeg.createThumbnail(inputFile = inputFile, timestamp = timestamp, outputFile = Some(outputFile), scaleHeight = Some(quality)).map(_ =>
        outputFile
      )
    case ImageThumbnail(width, height, quality)            =>
      logger.debug(s"Creating image thumbnail for $inputFile")
      imageMagick.resizeImage(inputFile = inputFile, outputFile = Some(outputFile), width = width, height = height).map(_ => outputFile)
}
