package nl.amony.service.resources

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString

trait IOResponse {
  def contentType(): String
  def size(): Long
  def getContent(): Source[ByteString, NotUsed]
  def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed]
}
