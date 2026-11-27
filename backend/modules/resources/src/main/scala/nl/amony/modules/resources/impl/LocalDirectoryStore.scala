package nl.amony.modules.resources.impl

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, Files, NoSuchFileException, Path, StandardCopyOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceContent, ResourceId, ResourcePath, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryConfig) extends ResourceStore, Logging:

  private def resolveKey(key: StoreKey): Either[String, Path] = key match
    case StoreKey.Resource(path)   => ResourcePath.fromString(path).map(config.resourcePath.resolve(_))
    case StoreKey.Derived(_, name) => Right(config.cachePath.resolve(name))

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
                logger.warn(s"Resource at '$path' was found in the database but no file exists: $resolved")
              case StoreKey.Derived(_, _)  => ()
            None
          }
        }

  override def put(key: StoreKey, contentType: Option[String], source: Path): EitherT[IO, String, StoredResource] =
    for
      target <- EitherT.fromEither[IO](resolveKey(key))
      stored <- moveSource(key, source, target, atomic = true)
    yield stored

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

  override def putUnique(fileName: String, source: Path): EitherT[IO, String, StoredResource] =
    for
      _         <- EitherT.fromEither[IO](ResourcePath.fromString(fileName))
      targetOpt <- EitherT.liftF(IO.blocking(resolveTargetPath(config.resourcePath.resolve(fileName), maxAttempts = 100)))
      target    <- EitherT.fromOption[IO](targetOpt, "Failed to resolve unique file name after 100 attempts")
      stored    <- moveSource(StoreKey.Resource(ResourcePath(fileName)), source, target, atomic = false)
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

  private def moveSource(key: StoreKey, source: Path, target: Path, atomic: Boolean): EitherT[IO, String, StoredResource] =
    EitherT(
      IO.blocking {
        Files.createDirectories(target.getParent)
        if atomic then
          try Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          catch case _: AtomicMoveNotSupportedException => Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source, target)
        readStoredResource(key, target)
      }.attempt.map {
        case Right(stored)                => Right(stored)
        case Left(_: NoSuchFileException) => Left(s"Source file '${source.getFileName}' is no longer available")
        case Left(error)                  => Left(s"Failed to store file '${source.getFileName}': ${error.getMessage}")
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
    StoredResource(
      path             = storedPath,
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
