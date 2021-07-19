package io.amony.actor

import io.amony.actor.MediaLibActor.{Collection, Media, State}

object MediaLibEventSourcing {

  sealed trait Event extends JsonSerializable

  case class MediaAdded(media: Media)           extends Event
  case class MediaUpdated(id: String, m: Media) extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media + (media.id -> media))

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))
    }
}
