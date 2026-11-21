package nl.amony.modules.resources.impl

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceContent, ResourceId, ResourceInfo, ResourcePath, StagingId, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryConfig) extends ResourceStore, Logging:

  override def content(info: ResourceInfo, eTag: Option[String]): IO[Option[ResourceContent]] =
    ResourcePath.fromString(info.path) match
      case Left(message) =>
        IO(logger.warn(s"Ignoring resource '${info.resourceId}' with invalid path: $message")).as(None)
      case Right(_)      =>
        IO.blocking(config.resourcePath.resolve(info.path)).map { path =>
          if Files.exists(path) then Some(ResourceContent.Streamed(StreamedResource.fromPath(path, info.contentType, eTag)))
          else {
            logger.warn(s"Resource '${info.resourceId}' was found in the database but no file exists: $path")
            None
          }
        }

  override def stage(stagingId: StagingId, file: Path): IO[Unit] = IO.unit

  override def commit(stagingId: StagingId, fileName: String): IO[Either[String, StoredResource]] =
    ResourcePath.fromString(fileName) match
      case Left(message) => IO.pure(Left(message))
      case Right(_)      =>
        IO.blocking {
          resolveTargetPath(config.resourcePath.resolve(fileName), maxAttempts = 100) match
            case None         => Left("Failed to resolve unique file name after 100 attempts")
            case Some(target) =>
              Files.createDirectories(target.getParent)
              Files.move(config.tempPath.resolve(stagingId), target)
              val attributes = Files.readAttributes(target, classOf[BasicFileAttributes])
              Right(
                StoredResource(
                  path             = ResourcePath(config.resourcePath.relativize(target).toString),
                  size             = attributes.size(),
                  timeLastModified = attributes.lastModifiedTime().toMillis,
                  fileKey          = Option(attributes.fileKey()).map(_.toString)
                )
              )
        }.attempt.map {
          case Right(result)                => result
          case Left(_: NoSuchFileException) => Left(s"Staged upload '$stagingId' is no longer available")
          case Left(error)                  => Left(s"Failed to commit staged upload '$stagingId': ${error.getMessage}")
        }

  override def discard(stagingId: StagingId): IO[Unit] =
    IO.blocking(Files.deleteIfExists(config.tempPath.resolve(stagingId))).void

  override def delete(path: ResourcePath): IO[Unit] =
    ResourcePath.fromString(path) match
      case Left(message) => IO.raiseError(new IllegalArgumentException(message))
      case Right(_)      => IO.blocking(Files.deleteIfExists(config.resourcePath.resolve(path))).void

  /** Deletes cached previews derived from a resource, so changed content is never served stale. */
  def deleteDerivedFiles(resourceId: ResourceId): IO[Unit] = IO.blocking {
    if Files.isDirectory(config.cachePath) then
      Using(Files.list(config.cachePath)) { stream =>
        stream.iterator().asScala
          .filter { path =>
            val name = path.getFileName.toString
            name.startsWith(resourceId.toString + "_") || name.startsWith(resourceId.toString + "-")
          }
          .foreach(path => Files.deleteIfExists(path))
      }
  }.void.handleErrorWith(e => IO(logger.warn(s"Failed to delete derived files for $resourceId", e)))

  private def resolveTargetPath(path: Path, maxAttempts: Int): Option[Path] = {
    var target: Path = path
    var counter: Int = 1

    while Files.exists(target) && counter < maxAttempts do
      val fileNameWithoutExt = target.getFileName.toString.lastIndexOf('.') match
        case -1  => target.getFileName.toString
        case idx => target.getFileName.toString.substring(0, idx)

      val extension = target.getFileName.toString.lastIndexOf('.') match
        case -1  => ""
        case idx => target.getFileName.toString.substring(idx)

      target = path.getParent.resolve(s"${fileNameWithoutExt}_$counter$extension")
      counter += 1

    Option.unless(Files.exists(target))(target)
  }
