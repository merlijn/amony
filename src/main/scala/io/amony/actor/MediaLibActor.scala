package io.amony.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import com.github.merlijn.amony.actor.MediaLibEventSourcing.Event
import com.github.merlijn.amony.lib.MediaLibConfig

import java.nio.file.Path

object MediaLibActor {

  sealed trait Command

  case class AddMedia(media: List[Media])                  extends Command
  case class AddCollections(collections: List[Collection]) extends Command

  case class GetAll(sender: ActorRef[List[Media]])                extends Command
  case class GetById(id: String, sender: ActorRef[Option[Media]]) extends Command
  case class GetCollections(sender: ActorRef[List[Collection]])   extends Command

  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command
  case class Query(q: Option[String], offset: Option[Int], n: Int, c: Option[String])
  case class SearchResult(offset: Int, total: Int, items: Seq[Media])

  case class SetThumbnail(mediaId: String, timeStamp: Long, sender: ActorRef[Option[Media]]) extends Command

  case class State(media: Map[String, Media], collections: Map[String, Collection])
  case class Thumbnail(timestamp: Long)
  case class Collection(id: String, title: String)

  case class Media(
      id: String,
      hash: String,
      uri: String,
      title: String,
      duration: Long,
      thumbnail: Thumbnail,
      resolution: (Int, Int),
      tags: Seq[String]
  ) {
    def path(baseDir: Path): Path = baseDir.resolve(uri)
  }

  def apply(config: MediaLibConfig): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId  = PersistenceId.ofUniqueId("mediaLib"),
      emptyState     = State(Map.empty, Map.empty),
      commandHandler = MediaLibCommandHandler.apply(config),
      eventHandler   = MediaLibEventSourcing.apply
    )
}
