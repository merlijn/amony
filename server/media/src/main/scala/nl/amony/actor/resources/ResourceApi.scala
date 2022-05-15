package nl.amony.actor.resources

import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.{ByteString, Timeout}
import nl.amony.actor.media.MediaApi
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.resources.ResourceApi.resourceServiceKey
import nl.amony.actor.resources.ResourcesProtocol._

import scala.concurrent.{ExecutionContext, Future}

object ResourceApi {

  def resourceBehaviour(config: MediaLibConfig, scanner: MediaScanner): Behavior[ResourceCommand] =
    LocalResourcesHandler.apply(config, scanner)

  val resourceServiceKey = ServiceKey[ResourceCommand]("resourceService")
}

class ResourceApi(val system: ActorSystem[Nothing], mediaApi: MediaApi) {

  implicit val ec: ExecutionContext = system.executionContext
  implicit val mat: Materializer    = SystemMaterializer.get(system).materializer
  implicit val scheduler            = system.scheduler

  private def resourceRef()(implicit timeout: Timeout): Future[ActorRef[ResourceCommand]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(resourceServiceKey, ref))(timeout, system.scheduler)
      .map( _.serviceInstances(resourceServiceKey).head)

  def uploadMedia(fileName: String, source: Source[ByteString, Any])(implicit timeout: Timeout): Future[Media] =
    resourceRef()
      .flatMap(_.ask[Media](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref)))
      .flatMap { m => mediaApi.upsertMedia(m) }

  private def getResource(mediaId: String)(fn: (Media, ActorRef[IOResponse]) => ResourceCommand)(implicit timeout: Timeout) =
    mediaApi
      .getById(mediaId)
      .flatMap {
        case None        => Future.successful(None)
        case Some(media) => resourceRef().flatMap(_.ask[IOResponse](ref => fn(media, ref)).map(Some(_)))
      }

  def getVideo(id: String, quality: Int)(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideo(media, ref))

  def getVideoFragment(id: String, start: Long, end: Long, quality: Int)(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideoFragment(media.id, (start, end), quality, ref))

  def getThumbnail(id: String, quality: Int, timestamp: Option[Long])(implicit timeout: Timeout): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetThumbnail(media.id, timestamp.getOrElse(media.fragments.head.fromTimestamp), quality, ref))

  def createFragments(media: Media)(implicit timeout: Timeout) =
    resourceRef().foreach(_.tell(ResourcesProtocol.CreateFragments(media, true)))
}
