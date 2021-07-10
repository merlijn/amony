package com.github.merlijn.kagera.actor

import akka.actor.typed.ActorRef
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import com.github.merlijn.kagera.http.Model.{Collection, SearchResult, Video}
import com.github.merlijn.kagera.actor.MediaLibEventSourcing.Event
import com.github.merlijn.kagera.lib.MediaLibConfig

object MediaLibActor {

  sealed trait Command

  case class AddMedia(media: List[Video])                         extends Command
  case class AddCollections(collections: List[Collection])        extends Command

  case class GetAll(sender: ActorRef[List[Video]])                extends Command
  case class GetById(id: String, sender: ActorRef[Option[Video]]) extends Command
  case class GetCollections(sender: ActorRef[List[Collection]])   extends Command

  case class Query(q: Option[String], offset: Option[Int], n: Int, c: Option[Int])
  case class Search(query: Query, sender: ActorRef[SearchResult]) extends Command

  case class SetThumbnail(mediaId: String, timeStamp: Long, sender: ActorRef[Option[Video]]) extends Command

  case class State(media: List[Video], collections: List[Collection])

  case class Media(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      thumbnail: String,
      resolution: String,
      tags: Seq[String]
  )

  def apply(config: MediaLibConfig): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("mediaLib"),
      emptyState = State(Nil, Nil),
      commandHandler = MediaLibCommandHandler.apply(config),
      eventHandler = MediaLibEventSourcing.apply
    )
}
