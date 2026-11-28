package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.IO
import fs2.io.file.{Files as Fs2Files, Path as Fs2Path}
import scribe.Logging

import nl.amony.modules.resources.api.{LocalDirectoryStorageConfig, ResourceContent, ResourceId, ResourcePath, StreamedResource}

/** [[ResourceStore]] backed by a local directory. */
final class LocalDirectoryStore(config: LocalDirectoryStorageConfig) extends ResourceStore, Logging:

  override def pathForUpload(resourceId: ResourceId, fileName: String): ResourcePath =
    LocalDirectoryStore.uploadTargetPath(fileName, resourceId)

  override def pathForReplacement(currentPath: ResourcePath, resourceId: ResourceId): ResourcePath =
    currentPath

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

object LocalDirectoryStore:

  private val maxFileNameBytes = 255

  private def utf8Length(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length

  private def truncateUtf8(value: String, maxBytes: Int): String =
    if maxBytes <= 0 then ""
    else if utf8Length(value) <= maxBytes then value
    else
      val sb    = new java.lang.StringBuilder
      var bytes = 0
      var i     = 0
      while i < value.length do
        val cp      = value.codePointAt(i)
        val cpChars = Character.charCount(cp)
        val cpBytes =
          if cp <= 0x7f then 1
          else if cp <= 0x7ff then 2
          else if cp <= 0xffff then 3
          else 4
        if bytes + cpBytes > maxBytes then i = value.length
        else
          sb.appendCodePoint(cp)
          bytes += cpBytes
          i += cpChars
      sb.toString.stripTrailing()

  private[impl] def uploadTargetPath(fileName: String, resourceId: ResourceId): ResourcePath =
    val dotIndex                = fileName.lastIndexOf('.')
    val (rawBase, rawExtension) =
      if dotIndex > 0 && dotIndex < fileName.length - 1 then
        (fileName.substring(0, dotIndex), fileName.substring(dotIndex))
      else
        (fileName, "")

    val idTag       = s"[$resourceId]"
    val idTagBytes  = utf8Length(idTag)
    val extBytes    = utf8Length(rawExtension)
    val (base, ext) =
      if idTagBytes + 1 + extBytes < maxFileNameBytes then (rawBase, rawExtension)
      else (fileName, "")

    val maxBaseBytes  = maxFileNameBytes - idTagBytes - 1 - utf8Length(ext)
    val truncatedBase = truncateUtf8(base, maxBaseBytes)
    val finalName     =
      if truncatedBase.isEmpty then s"$idTag$ext"
      else s"$truncatedBase $idTag$ext"
    ResourcePath(finalName)
