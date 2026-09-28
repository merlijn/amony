package nl.amony.modules.resources.local

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

import cats.effect.std.MapRef
import cats.effect.{Deferred, IO}
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.resources.api.*

trait LocalResourceOperations extends LocalDirectoryBase with Logging {

  type OperationKey = (resourceId: ResourceId, operation: ResourceOperation)

  extension (operation: ResourceOperation)
    def outputFile(resourceId: ResourceId): Path = {

      // Encode the pinned dimension so width- and height-based outputs never collide in the cache.
      def scaleSuffix(width: Option[Int], height: Option[Int]): String =
        width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

      val fileName = operation match
        case VideoFragment(width, height, start, end, quality)         => s"${resourceId}_$start-${end}_${scaleSuffix(width, height)}.mp4"
        case VideoThumbnail(width, height, quality, timestamp, format) => s"${resourceId}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
        case ImageThumbnail(width, height, quality, format)            => s"${resourceId}_${scaleSuffix(width, height)}.${format.extension}"

      config.cachePath.resolve(fileName)
    }

  private val mapRefOps: MapRef[IO, OperationKey, Option[Deferred[IO, Either[Throwable, Path]]]] =
    MapRef.fromConcurrentHashMap[IO, OperationKey, Deferred[IO, Either[Throwable, Path]]](
      new ConcurrentHashMap[OperationKey, Deferred[IO, Either[Throwable, Path]]]()
    )

  private[local] def derivedResource(info: ResourceInfo, operation: ResourceOperation): IO[Option[ResourceContent]] = {

    val outputFile = operation.outputFile(info.resourceId)
    val key        = (resourceId = info.resourceId, operation = operation)

    if Files.exists(outputFile) then IO.pure(ResourceContent.fromPath(outputFile, Some(key.operation.contentType)).some)
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
      }.flatten.map(_ => ResourceContent.fromPath(outputFile, Some(key.operation.contentType)).some)
    }
  }

  private def createResource(inputFile: Path, info: ResourceInfo, operation: ResourceOperation): IO[Path] =
    operation.validate(info) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(_)    => run(info, inputFile, operation.outputFile(info.resourceId), operation).memoize.flatten

  private def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] = operation match
    case VideoFragment(width, height, start, end, quality) =>
      logger.debug(s"Creating video fragment for $inputFile with range $start-$end")
      ffmpeg.transcodeToMp4(inputFile = inputFile, range = (start, end), scaleWidth = width, scaleHeight = height, outputFile = Some(outputFile)).map(_ =>
        outputFile
      )

    case VideoThumbnail(width, height, quality, timestamp, format) =>
      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp as ${format.configName}")
      ffmpeg.createThumbnail(inputFile = inputFile, timestamp = timestamp, outputFile = Some(outputFile), scaleWidth = width, scaleHeight = height).map(_ =>
        outputFile
      )
    case ImageThumbnail(width, height, quality, format)            =>
      logger.debug(s"Creating image thumbnail for $inputFile as ${format.configName}")
      ffmpeg.resizeImage(inputFile = inputFile, outputFile = Some(outputFile), width = width, height = height).map(_ => outputFile)
}
