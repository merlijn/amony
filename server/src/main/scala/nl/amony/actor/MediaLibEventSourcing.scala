package nl.amony.actor

import nl.amony.actor.MediaLibProtocol.{Directory, Fragment, Media, State}
import nl.amony.lib.ListOps
import scribe.Logging

object MediaLibEventSourcing extends Logging {

  sealed trait Event extends JsonSerializable

  case class MediaMetaDataUpdated(id: String, title: Option[String], comment: Option[String], tagsAdded: Set[String], tagsRemoved: Set[String])  extends Event
  case class MediaAdded(media: Media)                                                  extends Event
  case class MediaUpdated(id: String, m: Media)                                        extends Event
  case class MediaRemoved(id: String)                                                  extends Event

  case class FragmentDeleted(id: String, index: Int) extends Event
  case class FragmentAdded(id: String, fromTimeStamp: Long, toTimestamp: Long)         extends Event
  case class FragmentRangeUpdated(id: String, index: Int, fromTimestamp: Long, toTimestamp: Long) extends Event
  case class FragmentTagsUpdated(mediaId: String, fragmentId: Int, tags: List[String]) extends Event

  def apply(state: State, event: Event): State =
    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media + (media.id -> media))

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))

      case MediaMetaDataUpdated(mediaId, title, comment, tagsAdded, tagsRemoved) =>

        val media = state.media(mediaId)

        val newMedia = media.copy(
          title = title.orElse(media.title),
          comment = comment.orElse(media.comment),
          tags = media.tags ++ tagsAdded -- tagsRemoved
        )

        state.copy(media = state.media + (media.id -> newMedia))

      case MediaRemoved(id) =>
        state.copy(media = state.media - id)

      case FragmentAdded(id, from, to) =>
        val media        = state.media(id)
        val newFragments = Fragment(from, to, None, Nil) :: media.fragments

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))

      case FragmentRangeUpdated(id, index, from, to) =>
        val media = state.media(id)

        // check if specific range already exists
        val oldFragment      = media.fragments(index)
        val newFragment      = oldFragment.copy(fromTimestamp = from, toTimestamp = to)
        val primaryThumbnail = if (index == 0) from else media.thumbnailTimestamp
        val newMedia =
          media.copy(
            thumbnailTimestamp = primaryThumbnail,
            fragments          = media.fragments.replaceAtPos(index, newFragment)
          )

        state.copy(media = state.media + (media.id -> newMedia))

      case FragmentDeleted(id, index) =>
        val media = state.media(id)
        val newFragments = media.fragments.deleteAtPos(index)

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))

      case FragmentTagsUpdated(mediaId, fragmentId, tags) =>
        val media        = state.media(mediaId)
        val fragment     = media.fragments(fragmentId)
        val newFragments = media.fragments.replaceAtPos(fragmentId, fragment.copy(tags = tags))

        state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))
    }
}
