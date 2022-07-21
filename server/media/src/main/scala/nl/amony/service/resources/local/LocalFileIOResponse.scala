package nl.amony.service.resources.local

import akka.NotUsed
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceProtocol.IOResponse

import java.nio.file.{Files, Path}

case class LocalFileIOResponse(path: Path) extends IOResponse {
  override def contentType(): String = ContentTypeResolver.Default.apply(path.getFileName.toString).toString()
  override def size(): Long = Files.size(path)
  override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
  override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
    FileIO.fromPath(path, 8192, start).mapMaterializedValue(_ => NotUsed)
}

object LocalFileIOResponse {
  def option(path: Path): Option[LocalFileIOResponse] =
    Option.when(path.exists())(LocalFileIOResponse(path))
}