package nl.amony.api

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.{ByteString, Timeout}
import nl.amony.actor.Message
import nl.amony.actor.media.MediaLibProtocol.Media
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.{Materializer, SystemMaterializer}
import nl.amony.actor.resources.ResourcesProtocol._

import scala.concurrent.{ExecutionContext, Future}

class ResourceApi(val system: ActorSystem[Message], mediaApi: MediaApi) {

  implicit val ec: ExecutionContext = system.executionContext
  implicit val mat: Materializer    = SystemMaterializer.get(system).materializer
  implicit val scheduler            = system.scheduler

  def uploadMedia(fileName: String, source: Source[ByteString, Any])(implicit timeout: Timeout): Future[Media] =
    system
      .ask[Media](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))
      .flatMap { m => mediaApi.upsertMedia(m) }

  private def getResource(mediaId: String)(fn: (Media, ActorRef[IOResponse]) => ResourceCommand)(implicit timeout: Timeout) =
    mediaApi
      .getById(mediaId)
      .flatMap {
        case None        => Future.successful(None)
        case Some(media) => system.ask[IOResponse](ref => fn(media, ref)).map(Some(_))
      }

  def getVideo(id: String, quality: Int)(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideo(media, ref))

  def getVideoFragment(id: String, start: Long, end: Long, quality: Int)(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideoFragment(media.id, (start, end), quality, ref))

  def getThumbnail(id: String, quality: Int, timestamp: Option[Long])(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetThumbnail(media.id, timestamp.getOrElse(media.fragments.head.fromTimestamp), quality, ref))
}
