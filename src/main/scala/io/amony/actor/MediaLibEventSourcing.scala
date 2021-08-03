package io.amony.actor

import io.amony.actor.MediaLibActor.{Collection, Fragment, Media, State}
import scribe.Logging

object MediaLibEventSourcing extends Logging {

  sealed trait Event extends JsonSerializable

  case class MediaAdded(media: Media)           extends Event
  case class MediaUpdated(id: String, m: Media) extends Event
  case class MediaRemoved(id: String)           extends Event
  case class FragmentAdded(id: String, fromTimeStamp: Long, toTimestamp: Long) extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media + (media.id -> media))

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))

      case MediaRemoved(id) =>
        state.copy(media = state.media - id)

      case FragmentAdded(id, from, to) =>
        val media = state.media(id)
        val newFragments = Fragment(from, to, None, Nil) :: media.fragments

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))

    }
}
