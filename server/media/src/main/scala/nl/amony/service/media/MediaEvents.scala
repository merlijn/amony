package nl.amony.service.media

import nl.amony.service.media.MediaProtocol._
import scribe.Logging

object MediaEvents extends Logging {

  sealed trait Event

  case class MediaMetaDataUpdated(
      id: String,
      title: Option[String],
      comment: Option[String],
      tagsAdded: Set[String],
      tagsRemoved: Set[String]
  )                                             extends Event
  case class MediaAdded(media: Media)           extends Event
  case class MediaUpdated(id: String, m: Media) extends Event
  case class MediaRemoved(id: String)           extends Event

  def apply(state: State, event: Event): State = {

    logger.debug(s"Applying event: $event")

    event match {

      case MediaAdded(media) =>
        state.copy(media = state.media + (media.id -> media))

      case MediaUpdated(id, newVid) =>
        state.copy(media = state.media + (id -> newVid))

      case MediaMetaDataUpdated(mediaId, title, comment, tagsAdded, tagsRemoved) =>
        val media = state.media(mediaId)

        val newMeta = MediaMeta(
          title = title.orElse(media.meta.title),
          comment = comment.orElse(media.meta.comment),
          tags = media.meta.tags -- tagsRemoved ++ tagsAdded
        )

        state.copy(media = state.media + (media.id -> media.copy(meta = newMeta)))

      case MediaRemoved(id) =>
        state.copy(media = state.media - id)
    }
  }
}
