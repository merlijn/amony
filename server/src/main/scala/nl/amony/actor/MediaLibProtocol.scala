package nl.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibEventSourcing.Event
import nl.amony.lib.MediaScanner

import java.nio.file.Path

object MediaLibProtocol {

  sealed trait ErrorResponse

  case class MediaNotFound(id: String)      extends ErrorResponse
  case class InvalidCommand(reason: String) extends ErrorResponse

  sealed trait Command extends Message

  case class UpsertMedia(media: Media, sender: ActorRef[Boolean])                    extends Command
  case class RemoveMedia(id: String, deleteFile: Boolean, sender: ActorRef[Boolean]) extends Command

  // -- Querying
  case class GetAll(sender: ActorRef[List[Media]])                extends Command
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends Command
  case class GetByIds(ids: Set[String], sender: ActorRef[Map[String, Media]]) extends Command

  // --- Fragments
  case class DeleteFragment(mediaId: String, fragmentIdx: Int, sender: ActorRef[Either[ErrorResponse, Media]])
      extends Command
  case class UpdateFragmentRange(
      mediaId: String,
      fragmentIdx: Int,
      from: Long,
      to: Long,
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends Command
  case class AddFragment(mediaId: String, from: Long, to: Long, sender: ActorRef[Either[ErrorResponse, Media]])
      extends Command

  case class UpdateMetaData(
      mediaId: String,
      title: Option[String],
      comment: Option[String],
      tags: Set[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends Command

  case class UpdateFragmentTags(
      mediaId: String,
      fragmentIndex: Int,
      tags: List[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends Command

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

  def apply(config: MediaLibConfig, scanner: MediaScanner): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId  = PersistenceId.ofUniqueId("mediaLib"),
      emptyState     = State(Map.empty),
      commandHandler = MediaLibCommandHandler.apply(config, scanner),
      eventHandler   = MediaLibEventSourcing.apply
    )
}
