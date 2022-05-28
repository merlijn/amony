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

  case class DeleteResource(hash: String, sender: ActorRef[Boolean]) extends ResourceCommand
  case class Hash(`type`: String, hash: String)

  case class ResourceInfo(name: String, size: Long, createdTimestamp: Long, changedTimestamp: Long, hashes: Set[Hash])

  case class GetResourceIndex(sender: ActorRef[SourceRef[ResourceInfo]]) extends ResourceCommand

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

  case class GetPreviewSpriteImage(mediaId: String, sender: ActorRef[Option[IOResponse]]) extends ResourceCommand

  case class GetPreviewSpriteVtt(mediaId: String, sender: ActorRef[Option[String]]) extends ResourceCommand
}
