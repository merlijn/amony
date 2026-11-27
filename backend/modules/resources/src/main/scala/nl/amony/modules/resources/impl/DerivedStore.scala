package nl.amony.modules.resources.impl

import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.*

final case class DerivedKey(resourceId: ResourceId, name: String)

object DerivedKey:

  private def scaleSuffix(width: Option[Int], height: Option[Int]): String =
    width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

  def forOperation(resourceId: ResourceId, contentVersion: String, operation: ResourceOperation): DerivedKey =
    val name = operation match
      case VideoFragment(width, height, start, end)                         =>
        s"${resourceId}_${contentVersion}_$start-${end}_${scaleSuffix(width, height)}.mp4"
      case VideoThumbnail(width, height, timestamp, format)                 =>
        s"${resourceId}_${contentVersion}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
      case ImageThumbnail(width, height, format)                            =>
        s"${resourceId}_${contentVersion}_${scaleSuffix(width, height)}.${format.extension}"
      case VideoThumbnailTile(spritePixels, intervalInMillis, index, token) =>
        VideoThumbnailTile.spriteFileName(resourceId, contentVersion, spritePixels, intervalInMillis, index, token)
      case VideoThumbnailTileVtt(spritePixels, intervalInMillis, token)     =>
        VideoThumbnailTile.manifestFileName(resourceId, contentVersion, spritePixels, intervalInMillis, token)
      case AddFastStart(_)                                                  =>
        throw new IllegalArgumentException("AddFastStart does not produce a derived output file")
    DerivedKey(resourceId, name)

/** Storage for derived preview artifacts (thumbnails, clips, storyboard sprites and manifests). */
trait DerivedStore:
  def get(key: DerivedKey, contentType: String, eTag: Option[String]): IO[Option[ResourceContent]]
  def put(key: DerivedKey, contentType: String, source: Path): IO[Unit]
  def putBytes(key: DerivedKey, contentType: String, bytes: Array[Byte]): IO[Unit]
  def deleteForResource(resourceId: ResourceId): IO[Unit]

final class LocalDerivedStore(cachePath: Path) extends DerivedStore, Logging:

  override def get(key: DerivedKey, contentType: String, eTag: Option[String]): IO[Option[ResourceContent]] =
    IO.blocking {
      val file = cachePath.resolve(key.name)
      Option.when(Files.exists(file))(ResourceContent.Streamed(StreamedResource.fromPath(file, Some(contentType), eTag)))
    }

  override def put(key: DerivedKey, contentType: String, source: Path): IO[Unit] =
    val target = cachePath.resolve(key.name)
    IO.blocking(Files.createDirectories(target.getParent)) >> moveIntoPlace(source, target)

  override def putBytes(key: DerivedKey, contentType: String, bytes: Array[Byte]): IO[Unit] =
    val target = cachePath.resolve(key.name)
    val temp   = cachePath.resolve(s".${key.name}.${UUID.randomUUID()}")
    val write  =
      IO.blocking {
        Files.createDirectories(target.getParent)
        Files.write(temp, bytes)
      } >> moveIntoPlace(temp, target)
    write
      .onError(_ => IO.blocking(Files.deleteIfExists(temp)).void)
      .onCancel(IO.blocking(Files.deleteIfExists(temp)).void)

  override def deleteForResource(resourceId: ResourceId): IO[Unit] =
    IO.blocking {
      if Files.isDirectory(cachePath) then
        Using(Files.list(cachePath)) { stream =>
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
