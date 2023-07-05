package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.api.ResourceInfo
import org.http4s.MediaType

import java.nio.file.{Files, Path}

trait ResourceContent {
  def info(): ResourceInfo
  def contentType(): Option[String]
  def size(): Long
  def getContent(): fs2.Stream[IO, Byte]
  def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object ResourceContent {

  def contentTypeForPath(path: java.nio.file.Path): Option[String] = {
    val maybeExt = path.fileExtension
    maybeExt.flatMap { ext => MediaType.extensionMap.get(ext).map(m => s"${m.mainType}/${m.subType}") }
  }

  def fromPath(path: String, info: ResourceInfo): Option[LocalFileContent] =
    fromPath(java.nio.file.Path.of(path), info)

  def fromPath(path: java.nio.file.Path, info: ResourceInfo): Option[LocalFileContent] =
    Option.when(Files.exists(path))(LocalFileContent(fs2.io.file.Path.fromNioPath(path), info))
}

case class LocalFileContent(path: fs2.io.file.Path, info: ResourceInfo) extends ResourceContent {

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): Option[String] = ResourceContent.contentTypeForPath(path.toNioPath)

  override def size(): Long = Files.size(path.toNioPath)

  override def getContent(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
}
