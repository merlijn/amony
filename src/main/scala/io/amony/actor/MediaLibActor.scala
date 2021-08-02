package io.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.amony.actor.MediaLibEventSourcing.Event
import io.amony.lib.MediaLibConfig

import java.nio.file.Path

object MediaLibActor {

  sealed trait Command

  case class UpsertMedia(media: Media) extends Command
  case class RemoveMedia(id: String)   extends Command

  case class GetAll(sender: ActorRef[List[Media]])                extends Command
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends Command
  case class GetCollections(sender: ActorRef[List[Collection]])   extends Command

  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command
  case class Query(q: Option[String], offset: Option[Int], n: Int, c: Option[String])
  case class SearchResult(offset: Int, total: Int, items: Seq[Media])

  case class DeleteFragment(mediaId: String, fragmentIdx: Int, sender: ActorRef[Option[Media]]) extends Command
  case class UpdateFragment(mediaId: String, fragmentIdx: Int, from: Long, to: Long, sender: ActorRef[Option[Media]])
      extends Command
  case class AddFragment(mediaId: String, from: Long, to: Long, sender: ActorRef[Option[Media]]) extends Command

  case class State(media: Map[String, Media], collections: Map[String, Collection])
  case class Fragment(fromTimestamp: Long, toTimestamp: Long, comment: Option[String], tags: List[String])
  case class Collection(id: String, title: String)

  case class Media(
      id: String,
      hash: String,
      uri: String,
      title: Option[String],
      duration: Long,
      fps: Double,
      thumbnailTimestamp: Long,
      fragments: List[Fragment],
      resolution: (Int, Int),
      tags: List[String]
  ) {
    def path(baseDir: Path): Path = baseDir.resolve(uri)

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
