package nl.amony.service.resources

import cats.effect.IO
import nl.amony.service.resources.local.LocalFileUtil
import org.http4s.MediaType

import java.nio.file.{Files, Path}

trait IOResponse {
  def contentType(): Option[String]
  def size(): Long
  def getContent(): fs2.Stream[IO, Byte]
  def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object IOResponse {
  def fromPath(path: Path): Option[LocalFileIOResponse] =
    Option.when(Files.exists(path))(LocalFileIOResponse(fs2.io.file.Path.fromNioPath(path)))
}

case class LocalFileIOResponse(path: fs2.io.file.Path) extends IOResponse {

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): Option[String] = LocalFileUtil.contentTypeFromPath(path.toString)
  override def size(): Long = Files.size(path.toNioPath)

  override def getContent(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRange(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
}
