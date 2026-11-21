package nl.amony.modules.resources.impl

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceContent, ResourceInfo, ResourcePath, StagingId, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryConfig) extends ResourceStore, Logging:

  override def content(info: ResourceInfo, eTag: Option[String]): IO[Option[ResourceContent]] =
    IO.blocking(config.resourcePath.resolve(info.path)).map { path =>
      if Files.exists(path) then Some(ResourceContent.Streamed(StreamedResource.fromPath(path, info.contentType, eTag)))
      else {
        logger.warn(s"Resource '${info.resourceId}' was found in the database but no file exists: $path")
        None
      }
    }

  override def stage(stagingId: StagingId, file: Path): IO[Unit] = IO.unit

  override def commit(stagingId: StagingId, fileName: String): IO[Either[String, StoredResource]] =
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
    }

  override def discard(stagingId: StagingId): IO[Unit] = IO.unit

  override def delete(path: ResourcePath): IO[Unit] =
    IO.blocking(Files.deleteIfExists(config.resourcePath.resolve(path))).void

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
