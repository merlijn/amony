package nl.amony.service.resources

import cats.effect.IO
import nl.amony.service.resources.api.ResourceInfo

import java.nio.file.Files

trait Resource {
  def info(): ResourceInfo
  def contentType(): Option[String]
  def getContent(): fs2.Stream[IO, Byte]
}

trait ResourceWithRangeSupport extends Resource {
  def size(): Long
  def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object Resource {

  def fromPath(path: java.nio.file.Path, info: ResourceInfo): LocalFile =
    LocalFile(fs2.io.file.Path.fromNioPath(path), info)
  
  def fromPathMaybe(path: java.nio.file.Path, info: ResourceInfo): Option[LocalFile] =
    Option.when(Files.exists(path))(fromPath(path, info))
}

case class LocalFile(path: fs2.io.file.Path, resourceInfo: ResourceInfo) extends ResourceWithRangeSupport {

  override def info() = resourceInfo

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): Option[String] = resourceInfo.contentType

  override def size(): Long = Files.size(path.toNioPath)

  override def getContent(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
}
