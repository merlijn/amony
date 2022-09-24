package nl.amony.service.media

import MediaEvents.{MediaAdded, MediaRemoved}
import MediaProtocol._
import com.fasterxml.jackson.core.JsonEncoding
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import java.io.ByteArrayOutputStream
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class MediaService(dbConfig: DatabaseConfig[JdbcProfile]) extends Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  val mediaRepository = new MediaRepository(dbConfig)

  var eventListener: MediaEvents.Event => Unit = _ => ()

  def setEventListener(listener: MediaEvents.Event => Unit) = {
    eventListener = listener
  }

  def init(): Unit =
    Await.result(mediaRepository.createTables(), 5.seconds)

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
