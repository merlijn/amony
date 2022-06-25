package nl.amony.service.resources

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.ServiceKey
import akka.stream.SourceRef
import akka.stream.scaladsl.Source
import akka.util.ByteString
import nl.amony.service.media.actor.MediaLibProtocol.Media

object ResourceProtocol {

  sealed trait ResourceCommand

  object ResourceCommand {
    implicit val serviceKey: ServiceKey[ResourceCommand] = ServiceKey[ResourceCommand]("resourceService")
  }

  trait IOResponse {
    def contentType(): String
    def size(): Long
    def getContent(): Source[ByteString, NotUsed]
    def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed]
  }

  case class DeleteResource(media: Media, sender: ActorRef[Boolean]) extends ResourceCommand

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
