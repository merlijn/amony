package nl.amony.service.media

import nl.amony.service.media.api.protocol.{Media, MediaMeta}
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

  def apply(state: Map[String, Media], event: Event): Map[String, Media] = {

    logger.debug(s"Applying event: $event")

    event match {

      case MediaAdded(media) =>
        state + (media.mediaId -> media)

      case MediaUpdated(id, newVid) =>
        state + (id -> newVid)

      case MediaMetaDataUpdated(mediaId, title, comment, tagsAdded, tagsRemoved) =>
        val media = state(mediaId)

        val newMeta = MediaMeta(
          title = title.orElse(media.meta.title),
          comment = comment.orElse(media.meta.comment),
          tags = (media.meta.tags.toSet -- tagsRemoved ++ tagsAdded).toSeq.sortBy(media.meta.tags.indexOf)
        )

        state + (media.mediaId -> media.copy(meta = newMeta))

      case MediaRemoved(id) =>
        state - id
    }
  }
}
