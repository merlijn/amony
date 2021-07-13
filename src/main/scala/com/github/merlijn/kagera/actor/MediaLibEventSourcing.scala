package com.github.merlijn.kagera.actor

import com.github.merlijn.kagera.actor.MediaLibActor.{Collection, Media, State}

object MediaLibEventSourcing {

  sealed trait Event extends JsonSerializable

  case class MediaAdded(media: List[Media])                  extends Event
  case class CollectionsAdded(collections: List[Collection]) extends Event
  case class MediaUpdated(id: String, m: Media)              extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        val byId = media.map(m => m.id -> m).toMap
        state.copy(media = state.media ++ byId)

      case CollectionsAdded(collections) =>
        val byId = collections.map(m => m.id -> m).toMap
        state.copy(collections = state.collections ++ byId)

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))
    }
}
