package nl.amony.modules.resources.impl

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, FileAlreadyExistsException, Files, NoSuchFileException, Path, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.IO
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceContent, ResourceId, ResourcePath, StagingId, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryConfig) extends ResourceStore, Logging:

  private def resolveKey(key: StoreKey): Either[String, Path] = key match
    case StoreKey.Resource(path)   => ResourcePath.fromString(path).map(config.resourcePath.resolve(_))
    case StoreKey.Derived(_, name) => Right(config.cachePath.resolve(name))
    case StoreKey.Staged(id)       => Right(config.tempPath.resolve(id))

  override def content(key: StoreKey, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]] =
    resolveKey(key) match
      case Left(message)   =>
        IO(logger.warn(s"Ignoring resource with invalid key '$key': $message")).as(None)
      case Right(resolved) =>
        IO.blocking(Files.exists(resolved)).map { exists =>
          if exists then Some(ResourceContent.Streamed(StreamedResource.fromPath(resolved, contentType, eTag)))
          else {
            key match
              case StoreKey.Resource(path) =>
                logger.warn(s"Resource at '$path' does not exist at $resolved")
              case _                       => ()
            None
          }
        }

  override def exists(key: StoreKey): IO[Boolean] =
    resolveKey(key) match
      case Left(_)         => IO.pure(false)
      case Right(resolved) => IO.blocking(Files.exists(resolved))

  override def put(key: StoreKey, contentType: Option[String], source: fs2.Stream[IO, Byte]): EitherT[IO, String, Unit] =
    EitherT.fromEither[IO](resolveKey(key)).flatMap { target =>
      val temp  = target.resolveSibling(s".${target.getFileName}.${UUID.randomUUID()}")
      val write =
        IO.blocking(Files.createDirectories(target.getParent)) >>
          source.through(Fs2Files[IO].writeAll(Fs2Path.fromNioPath(temp))).compile.drain >>
          moveIntoPlace(temp, target)
      EitherT(
        write
          .onError(_ => IO.blocking(Files.deleteIfExists(temp)).void)
          .onCancel(IO.blocking(Files.deleteIfExists(temp)).void)
          .attempt
          .map(_.left.map(error => s"Failed to store file '${target.getFileName}': ${error.getMessage}"))
      )
    }

  override def putBytes(key: StoreKey, contentType: Option[String], bytes: Array[Byte]): IO[Unit] =
    resolveKey(key) match
      case Left(message) => IO.raiseError(new IllegalArgumentException(message))
      case Right(target) =>
        val temp  = target.resolveSibling(s".${target.getFileName}.${UUID.randomUUID()}")
        val write =
          IO.blocking {
            Files.createDirectories(target.getParent)
            Files.write(temp, bytes)
          } >> moveIntoPlace(temp, target)
        write
          .onError(_ => IO.blocking(Files.deleteIfExists(temp)).void)
          .onCancel(IO.blocking(Files.deleteIfExists(temp)).void)

  override def delete(key: StoreKey): IO[Unit] =
    resolveKey(key) match
      case Left(message)   => IO.raiseError(new IllegalArgumentException(message))
      case Right(resolved) => IO.blocking(Files.deleteIfExists(resolved)).void

  override def promote(stagingId: StagingId, target: ResourcePath, overwrite: Boolean = false): EitherT[IO, String, StoredResource] =
    for
      resolved <- EitherT.fromEither[IO](resolveKey(StoreKey.Resource(target)))
      stored   <- moveSource(StoreKey.Resource(target), config.tempPath.resolve(stagingId), resolved, overwrite)
    yield stored

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
          Option.when(Files.exists(resolved))(readStoredResource(StoreKey.Resource(path), resolved))
        }

  override def deleteDerived(resourceId: ResourceId): IO[Unit] =
    IO.blocking {
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

  override def deleteStaged(cutoffTime: Instant): IO[Unit] =
    IO.blocking {
      if Files.isDirectory(config.tempPath) then
        Using(Files.list(config.tempPath)) { stream =>
          stream.iterator().asScala
            .filter { path =>
              try Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant.isBefore(cutoffTime)
              catch case _: NoSuchFileException => false
            }
            .foreach(path => Files.deleteIfExists(path))
        }
    }.void.handleErrorWith(e => IO(logger.warn(s"Failed to delete staged files before $cutoffTime", e)))

  private def moveSource(key: StoreKey, source: Path, target: Path, overwrite: Boolean): EitherT[IO, String, StoredResource] =
    EitherT(
      IO.blocking {
        Files.createDirectories(target.getParent)
        if overwrite then
          try Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          catch case _: AtomicMoveNotSupportedException => Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source, target)
        readStoredResource(key, target)
      }.attempt.map {
        case Right(stored)                       => Right(stored)
        case Left(_: NoSuchFileException)        => Left(s"Staged file '${source.getFileName}' is no longer available")
        case Left(_: FileAlreadyExistsException) => Left(s"Target file '${target.getFileName}' already exists")
        case Left(error)                         => Left(s"Failed to store file '${source.getFileName}': ${error.getMessage}")
      }
    )

  private def moveIntoPlace(tempFile: Path, outputFile: Path): IO[Unit] =
    IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
      .recoverWith {
        case _: AtomicMoveNotSupportedException =>
          IO.blocking(Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING))
      }
      .void

  private def readStoredResource(key: StoreKey, target: Path): StoredResource =
    val attributes             = Files.readAttributes(target, classOf[BasicFileAttributes])
    val storedPath             = key match
      case StoreKey.Resource(_)      => ResourcePath(config.resourcePath.relativize(target).toString)
      case StoreKey.Derived(_, name) => ResourcePath(name)
      case StoreKey.Staged(id)       => ResourcePath(id)
    StoredResource(
      path             = storedPath,
      size             = attributes.size(),
      timeLastModified = attributes.lastModifiedTime().toMillis,
      fileKey          = Option(attributes.fileKey()).map(_.toString)
    )
