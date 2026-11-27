package nl.amony.modules.resources.impl

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, Files, NoSuchFileException, Path, StandardCopyOption}

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceContent, ResourcePath, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryConfig) extends ResourceStore, Logging:

  override def content(path: ResourcePath, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]] =
    ResourcePath.fromString(path) match
      case Left(message) =>
        IO(logger.warn(s"Ignoring resource with invalid path '$path': $message")).as(None)
      case Right(_)      =>
        IO.blocking(config.resourcePath.resolve(path)).map { resolved =>
          if Files.exists(resolved) then Some(ResourceContent.Streamed(StreamedResource.fromPath(resolved, contentType, eTag)))
          else {
            logger.warn(s"Resource at '$path' was found in the database but no file exists: $resolved")
            None
          }
        }

  override def openSource(path: ResourcePath): IO[Option[MediaSource]] =
    ResourcePath.fromString(path) match
      case Left(_)  => IO.pure(None)
      case Right(_) =>
        IO.blocking {
          val resolved = config.resourcePath.resolve(path)
          Option.when(Files.exists(resolved))(MediaSource.LocalFile(resolved))
        }

  override def stat(path: ResourcePath): IO[Option[StoredResource]] =
    ResourcePath.fromString(path) match
      case Left(_)  => IO.pure(None)
      case Right(_) =>
        IO.blocking {
          val resolved = config.resourcePath.resolve(path)
          Option.when(Files.exists(resolved))(readStoredResource(resolved))
        }

  override def put(fileName: String, source: Path): EitherT[IO, String, StoredResource] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(fileName))
      targetOpt <- EitherT.liftF(IO.blocking(resolveTargetPath(config.resourcePath.resolve(fileName), maxAttempts = 100)))
      target    <- EitherT.fromOption[IO](targetOpt, "Failed to resolve unique file name after 100 attempts")
      stored    <- moveSource(source, target, atomic = false)
    yield stored

  override def replace(path: ResourcePath, source: Path): EitherT[IO, String, StoredResource] =
    for
      _      <- EitherT.fromEither[IO](ResourcePath.fromString(path))
      target  = config.resourcePath.resolve(path)
      stored <- moveSource(source, target, atomic = true)
    yield stored

  override def delete(path: ResourcePath): IO[Unit] =
    ResourcePath.fromString(path) match
      case Left(message) => IO.raiseError(new IllegalArgumentException(message))
      case Right(_)      => IO.blocking(Files.deleteIfExists(config.resourcePath.resolve(path))).void

  private def moveSource(source: Path, target: Path, atomic: Boolean): EitherT[IO, String, StoredResource] =
    EitherT(
      IO.blocking {
        Files.createDirectories(target.getParent)
        if atomic then
          try Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          catch case _: AtomicMoveNotSupportedException => Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source, target)
        readStoredResource(target)
      }.attempt.map {
        case Right(stored)                => Right(stored)
        case Left(_: NoSuchFileException) => Left(s"Source file '${source.getFileName}' is no longer available")
        case Left(error)                  => Left(s"Failed to store file '${source.getFileName}': ${error.getMessage}")
      }
    )

  private def readStoredResource(target: Path): StoredResource =
    val attributes             = Files.readAttributes(target, classOf[BasicFileAttributes])
    StoredResource(
      path             = ResourcePath(config.resourcePath.relativize(target).toString),
      size             = attributes.size(),
      timeLastModified = attributes.lastModifiedTime().toMillis,
      fileKey          = Option(attributes.fileKey()).map(_.toString)
    )

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
