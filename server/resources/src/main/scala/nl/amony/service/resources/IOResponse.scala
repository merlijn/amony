package nl.amony.service.resources

import akka.NotUsed
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString

import java.nio.file.{Files, Path}

trait IOResponse {
  def contentType(): String
  def size(): Long
  def getContent(): Source[ByteString, NotUsed]
  def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed]
}

object IOResponse {
  def fromPath(path: Path): Option[LocalFileIOResponse] =
    Option.when(Files.exists(path))(LocalFileIOResponse(path))
}

case class LocalFileIOResponse(path: Path) extends IOResponse {
  override def contentType(): String = ContentTypeResolver.Default.apply(path.getFileName.toString).toString()
  override def size(): Long = Files.size(path)
  override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
  override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
    FileIO.fromPath(path, 8192, start).mapMaterializedValue(_ => NotUsed)
}
