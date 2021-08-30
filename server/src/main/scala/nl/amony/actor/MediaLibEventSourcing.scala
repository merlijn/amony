package nl.amony.actor

import nl.amony.actor.MediaLibActor.{Collection, Fragment, Media, State}
import nl.amony.lib.ListOps
import scribe.Logging

object MediaLibEventSourcing extends Logging {

  sealed trait Event extends JsonSerializable

  case class MediaAdded(media: Media)                                                  extends Event
  case class MediaUpdated(id: String, m: Media)                                        extends Event
  case class MediaRemoved(id: String)                                                  extends Event
  case class FragmentAdded(id: String, fromTimeStamp: Long, toTimestamp: Long)         extends Event
  case class FragmentTagsUpdated(mediaId: String, fragmentId: Int, tags: List[String]) extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media + (media.id -> media))

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))

      case MediaRemoved(id) =>
        state.copy(media = state.media - id)

      case FragmentAdded(id, from, to) =>
        val media        = state.media(id)
        val newFragments = Fragment(from, to, None, Nil) :: media.fragments

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))

      case FragmentTagsUpdated(mediaId, fragmentId, tags) =>
        val media        = state.media(mediaId)
        val fragment     = media.fragments(fragmentId)
        val newFragments = media.fragments.replaceAtPos(fragmentId, fragment.copy(tags = tags))

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))

    }
}
