package nl.amony.service.media

import com.fasterxml.jackson.core.JsonEncoding
import nl.amony.lib.eventbus.EventTopic
import nl.amony.service.media.api._
import nl.amony.service.media.api.events._
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future

class MediaService(mediaRepository: MediaStorage[_], mediaTopic: EventTopic[MediaEvent]) extends Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  // temporary hack
  private def addFormats(media: Media): Media = {
    media.copy(availableFormats = List(
      MediaType("mp4_lowres", "video/mp4", 320)
    ))
  }

  def getById(id: String): Future[Option[Media]] = {
    mediaRepository.getById(id).map(_.map(addFormats))
  }

  def getAll(): Future[Seq[Media]] = {
    mediaRepository.getAll().map(_.map(addFormats))
  }

  def exportToJson(): Future[String] = {

    import com.fasterxml.jackson.databind.ObjectMapper
    val objectMapper = new ObjectMapper

    getAll().map { medias =>
      val out = new ByteArrayOutputStream()
      objectMapper.createGenerator(out, JsonEncoding.UTF8).useDefaultPrettyPrinter().writeObject(medias)
      out.toString()
    }
  }

  def upsertMedia(media: Media): Future[Media] = {
    mediaRepository.upsert(media).map { media =>
      mediaTopic.publish(MediaAdded(media))
      media
    }
  }

  def deleteMedia(id: String, deleteResource: Boolean): Future[Boolean] = {
    mediaRepository.deleteById(id).map { n =>
      logger.info(s"Media removed: ${id}")
      mediaTopic.publish(MediaRemoved(id))
      n > 0
    }
  }

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String]): Future[Option[Media]] = {

    mediaRepository.getById(id).flatMap {
      case None        => Future.successful(None)
      case Some(oldMedia) =>
        mediaRepository.upsert(oldMedia.copy(meta = MediaMeta(title, comment, tags))).map { m =>
          val tagsAdded = tags.filterNot(oldMedia.meta.tags.contains)
          val tagsRemoved = oldMedia.meta.tags.filterNot(tags.contains)
          mediaTopic.publish(MediaMetaDataUpdated(id, title, comment, tagsAdded, tagsRemoved))
          Some(m)
        }
    }
  }
}
