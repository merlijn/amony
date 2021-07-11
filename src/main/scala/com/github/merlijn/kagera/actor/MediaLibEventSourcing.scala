package com.github.merlijn.kagera.actor

import com.github.merlijn.kagera.actor.MediaLibActor.{Collection, Media, State}

object MediaLibEventSourcing {

  sealed trait Event extends JsonSerializable

  case class MediaAdded(media: List[Media])                  extends Event
  case class CollectionsAdded(collections: List[Collection]) extends Event
  case class ReplaceVid(id: String, v: Media)                extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media ::: media)

      case CollectionsAdded(collections) =>
        state.copy(collections = state.collections ::: collections)

      case ReplaceVid(id, newVid) =>
        // replace vid
        val idx = state.media.indexWhere(_.id == id)
        val newMedia =
          state.media.slice(0, idx) ::: (newVid :: state.media.slice(idx + 1, state.media.size))
        state.copy(media = newMedia)
    }
}
