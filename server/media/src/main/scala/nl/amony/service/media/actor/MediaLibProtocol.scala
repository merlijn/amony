package nl.amony.service.media.actor

import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.ServiceKey
import nl.amony.service.fragments.Protocol.Fragment

import java.nio.file.Path

object MediaLibProtocol {

  sealed trait ErrorResponse

  case class MediaNotFound(id: String)      extends ErrorResponse
  case class InvalidCommand(reason: String) extends ErrorResponse

  sealed trait MediaCommand

  object MediaCommand {
    implicit val serviceKey: ServiceKey[MediaCommand] = ServiceKey[MediaCommand]("mediaService")
  }

  case class UpsertMedia(media: Media, sender: ActorRef[Boolean])                    extends MediaCommand
  case class RemoveMedia(id: String, deleteFile: Boolean, sender: ActorRef[Boolean]) extends MediaCommand

  // -- Querying
  case class GetAll(sender: ActorRef[List[Media]])                extends MediaCommand
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends MediaCommand

  case class UpdateMetaData(
      mediaId: String,
      title: Option[String],
      comment: Option[String],
      tags: Set[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends MediaCommand

  // -- State
  case class State(media: Map[String, Media])

  case class ResourceInfo(
      bucketId: String,
      relativePath: String,
      hash: String,
      size: Long
  ) {
    def extension: String = relativePath.split('.').last
  }

  case class MediaInfo(
    fps: Double,
    videoCodec: String,
    duration: Long,
    resolution: (Int, Int)
  )

  case class MediaMeta(
    title: Option[String],
    comment: Option[String],
    tags: Set[String]
  )

  case class Media(
      id: String,
      uploader: String,
      uploadTimestamp: Long,
      resourceInfo: ResourceInfo,
      videoInfo: MediaInfo,
      meta: MediaMeta,
      thumbnailTimestamp: Long,
      fragments: List[Fragment],
  ) {
    def resolvePath(baseDir: Path): Path = baseDir.resolve(resourceInfo.relativePath)

    def width: Int = videoInfo.resolution._1
    def height: Int = videoInfo.resolution._2

    def fileName(): String = {
      val slashIdx = resourceInfo.relativePath.lastIndexOf('/')
      val dotIdx   = resourceInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx   = if (dotIdx >= 0) dotIdx else resourceInfo.relativePath.length

      resourceInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}
