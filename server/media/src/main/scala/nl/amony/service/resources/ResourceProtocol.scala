package nl.amony.service.resources

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.SourceRef
import akka.stream.scaladsl.Source
import akka.util.ByteString
import nl.amony.service.media.actor.MediaLibProtocol.Media

object ResourceProtocol {

  sealed trait ResourceCommand

  trait IOResponse {
    def size(): Long
    def getContent(): Source[ByteString, NotUsed]
    def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed]
  }

  case class FileInfo(fileName: String, contentType: String, size: Long, createdTimestamp: Long, hashes: List[String])

  case class Upload(fileName: String, source: SourceRef[ByteString], sender: ActorRef[Media]) extends ResourceCommand


  case class CreateFragments(media: Media, overwrite: Boolean) extends ResourceCommand
  case class CreateFragment(media: Media, timeRange: (Long, Long), overwrite: Boolean, sender: ActorRef[Boolean])
      extends ResourceCommand
  case class DeleteFragment(media: Media, timeRange: (Long, Long)) extends ResourceCommand


  case class GetThumbnail(mediaHash: String, timestamp: Long, quality: Int, sender: ActorRef[IOResponse])
      extends ResourceCommand
  case class GetVideoFragment(mediaHash: String, timeRange: (Long, Long), quality: Int, sender: ActorRef[IOResponse])
      extends ResourceCommand
  case class GetVideo(media: Media, sender: ActorRef[IOResponse]) extends ResourceCommand
}
