package nl.amony.service.resources

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.ServiceKey
import akka.stream.SourceRef
import akka.stream.scaladsl.Source
import akka.util.ByteString
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.ProbeOutput
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

  case class DeleteResource(resourceHash: String, sender: ActorRef[Boolean]) extends ResourceCommand
}
