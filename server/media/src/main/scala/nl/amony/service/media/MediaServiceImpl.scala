package nl.amony.service.media

import com.fasterxml.jackson.core.JsonEncoding
import io.grpc.{Status, StatusRuntimeException}
import io.grpc.stub.StreamObserver
import nl.amony.lib.eventbus.EventTopic
import nl.amony.service.media.api.MediaServiceGrpc.MediaService
import nl.amony.service.media.api._
import nl.amony.service.media.api.events._
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future

class MediaServiceImpl(mediaRepository: MediaStorage[_], mediaTopic: EventTopic[MediaEvent]) extends MediaService with Logging {

  import scala.concurrent.ExecutionContext.Implicits.global

  // temporary hack
  private def addFormats(media: Media): Media = {
    media.copy(availableFormats = List(MediaType("mp4_lowres", "video/mp4", 320)))
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

  private def notFound[T](mediaId: String): Future[T] =
    Future.failed[T](new StatusRuntimeException(Status.fromCode(Status.Code.NOT_FOUND).withDescription(s"Media not found: ${mediaId}")))

  override def getById(request: GetById): Future[Media] =
    mediaRepository.getById(request.mediaId).flatMap {
      case Some(media) => Future.successful(addFormats(media))
      case None        => notFound(request.mediaId)
    }

  override def getAll(request: GetAllMedia, responseObserver: StreamObserver[Media]): Unit = ???

  override def deleteById(request: DeleteById): Future[DeleteByIdResponse] = {
    mediaRepository.deleteById(request.mediaId).flatMap {
      case 0 => notFound(request.mediaId)
      case 1 => Future.successful(DeleteByIdResponse())
    }
  }

  override def updateMediaMeta(req: UpdateMediaMeta): Future[Media] = {
    mediaRepository.getById(req.mediaId).flatMap {
      case None           => notFound(req.mediaId)
      case Some(oldMedia) =>

        mediaRepository.upsert(oldMedia.copy(meta = MediaMeta(req.meta.title, req.meta.comment, req.meta.tags))).map { m =>
          val tagsAdded = req.meta.tags.filterNot(oldMedia.meta.tags.contains)
          val tagsRemoved = oldMedia.meta.tags.filterNot(req.meta.tags.contains)
          mediaTopic.publish(MediaMetaDataUpdated(req.mediaId, req.meta.title, req.meta.comment, tagsAdded, tagsRemoved))
          m
        }
    }
  }
}
