package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.files.PathOps
import org.http4s.MediaType

import java.nio.file.{Files, Path}

trait ResourceContent {
  def contentType(): Option[String]
  def size(): Long
  def getContent(): fs2.Stream[IO, Byte]
  def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object ResourceContent {

  def fromPath(path: String): Option[LocalFileContent] =
    fromPath(java.nio.file.Path.of(path))

  def fromPath(path: java.nio.file.Path): Option[LocalFileContent] =
    Option.when(Files.exists(path))(LocalFileContent(fs2.io.file.Path.fromNioPath(path)))
}

case class LocalFileContent(path: fs2.io.file.Path) extends ResourceContent {

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): Option[String] = {
    val maybeExt = java.nio.file.Path.of(path.toString).fileExtension
    maybeExt.flatMap { ext => MediaType.extensionMap.get(ext).map(m => s"${m.mainType}/${m.subType}") }
  }

  override def size(): Long = Files.size(path.toNioPath)

  override def getContent(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
}
