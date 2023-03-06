package nl.amony.service.resources

import akka.NotUsed
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import cats.effect.IO

import java.nio.file.{Files, Path}

trait IOResponse {
  def contentType(): String
  def size(): Long
  def getContent(): Source[ByteString, NotUsed]
  def getContentFs2(): fs2.Stream[IO, Byte]
  def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed]
}

object IOResponse {
  def fromPath(path: Path): Option[LocalFileIOResponse] =
    Option.when(Files.exists(path))(LocalFileIOResponse(path))
}

case class LocalFileIOResponse(path: Path) extends IOResponse {

  private val defaultChunkSize: Int = 64 * 1024

  override def contentType(): String = ContentTypeResolver.Default.apply(path.getFileName.toString).toString()
  override def size(): Long = Files.size(path)
  override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
  override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
    FileIO.fromPath(path, defaultChunkSize, start).mapMaterializedValue(_ => NotUsed)

  override def getContentFs2(): fs2.Stream[IO, Byte] =
    fs2.io.file.Files[IO].readAll(fs2.io.file.Path.fromNioPath(path), defaultChunkSize, fs2.io.file.Flags.Read)
}
