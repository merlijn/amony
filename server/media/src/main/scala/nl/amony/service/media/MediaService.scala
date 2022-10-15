package nl.amony.service.media

import com.fasterxml.jackson.core.JsonEncoding
import nl.amony.service.media.MediaEvents.{MediaAdded, MediaRemoved}
import nl.amony.service.media.MediaProtocol._
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future

class MediaService(mediaRepository: MediaRepository[_]) extends Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  var eventListener: MediaEvents.Event => Unit = _ => ()

  def setEventListener(listener: MediaEvents.Event => Unit) = {
    eventListener = listener
    getAll().foreach { medias =>
      medias.foreach(m => eventListener.apply(MediaAdded(m)))
    }
  }

  def getById(id: String): Future[Option[Media]] = {
    mediaRepository.getById(id)
  }

  def getAll(): Future[Seq[Media]] = {
    mediaRepository.getAll()
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
      eventListener.apply(MediaAdded(media))
      media
    }
  }

  def deleteMedia(id: String, deleteResource: Boolean): Future[Boolean] = {
    mediaRepository.deleteById(id).map { n =>
      logger.info(s"Media removed: ${id}")
      eventListener(MediaRemoved(id))
      n > 0
    }
  }

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String]): Future[Either[ErrorResponse, Media]] = {

    mediaRepository.getById(id).flatMap {
      case None        => Future.successful(Left(MediaNotFound(id)))
      case Some(media) => mediaRepository.upsert(media.copy(meta = MediaMeta(title, comment, tags.toSet))).map(Right(_))
    }
  }
}
