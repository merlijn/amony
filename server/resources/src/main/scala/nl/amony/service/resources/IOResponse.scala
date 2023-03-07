package nl.amony.service.resources

import cats.effect.IO
import org.http4s.MediaType

import java.nio.file.{Files, Path}

trait IOResponse {
  def contentType(): String
  def size(): Long
  def getContentFs2(): fs2.Stream[IO, Byte]
  def getContentRangeFs2(start: Long, end: Long): fs2.Stream[IO, Byte]
}

object IOResponse {
  def fromPath(path: Path): Option[LocalFileIOResponse] =
    Option.when(Files.exists(path))(LocalFileIOResponse(path))
}

case class LocalFileIOResponse(path: Path) extends IOResponse {

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): String = "" //MediaType.extensionMap.get()
  override def size(): Long = Files.size(path)

  override def getContentFs2(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(fs2.io.file.Path.fromNioPath(path), defaultChunkSize, fs2.io.file.Flags.Read)

  override def getContentRangeFs2(start: Long, end: Long): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readRange(fs2.io.file.Path.fromNioPath(path), defaultChunkSize, start, end)
}
