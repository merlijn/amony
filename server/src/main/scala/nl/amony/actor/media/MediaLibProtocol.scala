package nl.amony.actor.media

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import nl.amony.MediaLibConfig
import nl.amony.actor.Message
import nl.amony.actor.media.MediaLibEventSourcing.Event

import java.nio.file.Path

object MediaLibProtocol {

  sealed trait ErrorResponse

  case class MediaNotFound(id: String)      extends ErrorResponse
  case class InvalidCommand(reason: String) extends ErrorResponse

  sealed trait MediaCommand extends Message

  case class UpsertMedia(media: Media, sender: ActorRef[Boolean])                    extends MediaCommand
  case class RemoveMedia(id: String, deleteFile: Boolean, sender: ActorRef[Boolean]) extends MediaCommand

  // -- Querying
  case class GetAll(sender: ActorRef[List[Media]])                extends MediaCommand
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends MediaCommand

  // --- Fragments
  case class DeleteFragment(mediaId: String, fragmentIdx: Int, sender: ActorRef[Either[ErrorResponse, Media]])
      extends MediaCommand
  case class UpdateFragmentRange(
      mediaId: String,
      fragmentIdx: Int,
      from: Long,
      to: Long,
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends MediaCommand
  case class AddFragment(mediaId: String, from: Long, to: Long, sender: ActorRef[Either[ErrorResponse, Media]])
      extends MediaCommand

  case class UpdateMetaData(
      mediaId: String,
      title: Option[String],
      comment: Option[String],
      tags: Set[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends MediaCommand

  case class UpdateFragmentTags(
      mediaId: String,
      fragmentIndex: Int,
      tags: List[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends MediaCommand

  // -- State
  case class State(media: Map[String, Media])
  case class Fragment(fromTimestamp: Long, toTimestamp: Long, comment: Option[String], tags: List[String])

  case class FileInfo(
      relativePath: String,
      hash: String,
      size: Long,
      creationTime: Long,
      lastModifiedTime: Long
  ) {
    def extension: String = relativePath.split('.').last
  }

  case class VideoInfo(
    fps: Double,
    duration: Long,
    resolution: (Int, Int)
  )

  case class Media(
      id: String,
      title: Option[String],
      comment: Option[String],
      fileInfo: FileInfo,
      videoInfo: VideoInfo,
      // TODO remove
      thumbnailTimestamp: Long,
      fragments: List[Fragment],
      tags: Set[String]
  ) {
    def resolvePath(baseDir: Path): Path = baseDir.resolve(fileInfo.relativePath)

    def width: Int = videoInfo.resolution._1
    def height: Int = videoInfo.resolution._2

    def fileName(): String = {
      val slashIdx = fileInfo.relativePath.lastIndexOf('/')
      val dotIdx   = fileInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx   = if (dotIdx >= 0) dotIdx else fileInfo.relativePath.length

      fileInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}
