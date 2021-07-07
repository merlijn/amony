package com.github.merlijn.kagera.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import com.github.merlijn.kagera.MediaLibConfig
import com.github.merlijn.kagera.Model.{Collection, SearchResult, Video}
import com.github.merlijn.kagera.actor.Events.Event

object MediaLibActor {

  sealed trait Command

  case class AddMedia(media: List[Video]) extends Command
  case class AddCollections(collections: List[Collection]) extends Command
  case class GetById(id: String, sender: ActorRef[Option[Video]]) extends Command

  case class Query(q: Option[String], page: Int, size: Int, c: Option[Int])
  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command

  case class SetThumbnail(id: String, timeStamp: Long, sender: ActorRef[Option[Video]]) extends Command

  case class State(media: List[Video], collections: List[Collection])

  def apply(config: MediaLibConfig): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("mediaLib"),
      emptyState = State(Nil, Nil),
      commandHandler = MediaLibHandler(config),
      eventHandler = MediaLibJournal)
}
