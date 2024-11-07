package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.api.ResourceInfo
import org.http4s.MediaType

import java.nio.file.{Files, Path}

trait Resource {
  def info(): ResourceInfo
  def contentType(): Option[String]
  def size(): Long
  def getContent(): fs2.Stream[IO, Byte]
  def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object Resource {

  def contentTypeForPath(path: java.nio.file.Path): Option[String] = {
    val maybeExt = path.fileExtension()
    maybeExt.flatMap { ext => MediaType.extensionMap.get(ext).map(m => s"${m.mainType}/${m.subType}") }
  }

  def fromPath(path: java.nio.file.Path, info: ResourceInfo): LocalFile =
    LocalFile(fs2.io.file.Path.fromNioPath(path), info)
  
  def fromPathMaybe(path: java.nio.file.Path, info: ResourceInfo): Option[LocalFile] =
    Option.when(Files.exists(path))(fromPath(path, info))
}

case class LocalFile(path: fs2.io.file.Path, resourceInfo: ResourceInfo) extends Resource {

  override def info() = resourceInfo

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): Option[String] = Resource.contentTypeForPath(path.toNioPath)

  override def size(): Long = Files.size(path.toNioPath)

  override def getContent(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
}
