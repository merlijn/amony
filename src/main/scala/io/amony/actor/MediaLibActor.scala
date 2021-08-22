package io.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.amony.MediaLibConfig
import io.amony.actor.MediaLibEventSourcing.Event

import java.nio.file.Path

object MediaLibActor {

  sealed trait ErrorResponse

  case class MediaNotFound(id: String)      extends ErrorResponse
  case class InvalidCommand(reason: String) extends ErrorResponse

  sealed trait Command

  case class UpsertMedia(media: Media, sender: ActorRef[Boolean]) extends Command
  case class RemoveMedia(id: String, sender: ActorRef[Boolean])   extends Command

  // -- Querying
  case class GetAll(sender: ActorRef[List[Media]])                extends Command
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends Command
  case class GetTags(sender: ActorRef[List[Collection]])          extends Command

  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command
  case class Query(q: Option[String], offset: Option[Int], n: Int, tag: Option[String], minRes: Option[Int])
  case class SearchResult(offset: Int, total: Int, items: Seq[Media])

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
  case class UpdateFragmentTags(
      mediaId: String,
      fragmentIndex: Int,
      tags: List[String],
      sender: ActorRef[Either[ErrorResponse, Media]]
  ) extends Command

  // -- State
  case class State(media: Map[String, Media], collections: Map[String, Collection])
  case class Fragment(fromTimestamp: Long, toTimestamp: Long, comment: Option[String], tags: List[String])
  case class Collection(id: String, title: String)

  case class Media(
      id: String,
      hash: String,
      uri: String,
      addedOnTimestamp: Long,
      title: Option[String],
      duration: Long,
      fps: Double,
      thumbnailTimestamp: Long,
      fragments: List[Fragment],
      resolution: (Int, Int),
      tags: List[String]
  ) {
    def resolvePath(baseDir: Path): Path = baseDir.resolve(uri)

    def fileName(): String = {
      val slashIdx = uri.lastIndexOf('/')
      val dotIdx   = uri.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx   = if (dotIdx >= 0) dotIdx else uri.length

      uri.substring(startIdx, endIdx)
    }
  }

  def apply(config: MediaLibConfig): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId  = PersistenceId.ofUniqueId("mediaLib"),
      emptyState     = State(Map.empty, Map.empty),
      commandHandler = MediaLibCommandHandler.apply(config),
      eventHandler   = MediaLibEventSourcing.apply
    )
}
