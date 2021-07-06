package com.github.merlijn.webapp.actor

import com.github.merlijn.webapp.Model.{Collection, Video}
import com.github.merlijn.webapp.actor.MediaLibActor.State

sealed trait Event

case class MediaAdded(media: List[Video]) extends Event
case class CollectionsAdded(collections: List[Collection]) extends Event
case class ReplaceVid(id: String, v: Video) extends Event

object MediaLibJournal {
  def apply(state: State, event: Event): State = event match {
    case MediaAdded(media) =>
      state.copy(media = state.media ::: media)
    case CollectionsAdded(collections) =>
      state.copy(collections = state.collections ::: collections)
    case ReplaceVid(id, newVid) =>
      // replace vid
      val idx = state.media.indexWhere(_.id == id)
      val newMedia = state.media.slice(0, idx) ::: (newVid :: state.media.slice(idx + 1, state.media.size))
      state.copy(
        media = newMedia
      )
  }
}
