package nl.amony.actor.resources

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamRefs
import akka.util.ByteString
import akka.util.Timeout
import nl.amony.actor.media.MediaApi
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.resources.ResourceApi.resourceServiceKey
import nl.amony.actor.resources.ResourcesProtocol._
import nl.amony.actor.resources.local.{LocalMediaScanner, LocalResourcesHandler}
import nl.amony.lib.akka.AkkaServiceModule

import scala.concurrent.Future

object ResourceApi {

  def resourceBehaviour(config: MediaLibConfig, scanner: LocalMediaScanner): Behavior[ResourceCommand] = {

    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(resourceServiceKey, context.self)
      LocalResourcesHandler.apply(config, scanner)
    }
  }

  val resourceServiceKey = ServiceKey[ResourceCommand]("resourceService")
}

class ResourceApi(override val system: ActorSystem[Nothing], override implicit val askTimeout: Timeout, mediaApi: MediaApi)
    extends AkkaServiceModule[ResourceCommand] {

  override val serviceKey: ServiceKey[ResourceCommand] = resourceServiceKey

  def uploadMedia(fileName: String, source: Source[ByteString, Any]): Future[Media] =
    askService[Media](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))
      .flatMap(mediaApi.upsertMedia)

  private def getResource(
      mediaId: String
  )(fn: (Media, ActorRef[IOResponse]) => ResourceCommand): Future[Option[IOResponse]] =
    mediaApi
      .getById(mediaId)
      .flatMap {
        case None        => Future.successful(None)
        case Some(media) => serviceRef().flatMap(_.ask[IOResponse](ref => fn(media, ref)).map(Some(_)))
      }

  def getVideo(id: String, quality: Int): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideo(media, ref))

  def getVideoFragment(id: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]] =
    getResource(id)((media, ref) => GetVideoFragment(media.id, (start, end), quality, ref))

  def getThumbnail(id: String, quality: Int, timestamp: Option[Long]): Future[Option[IOResponse]] =
    getResource(id)((media, ref) =>
      GetThumbnail(media.id, timestamp.getOrElse(media.fragments.head.fromTimestamp), quality, ref)
    )

  def createFragments(media: Media) =
    serviceRef().foreach(_.tell(ResourcesProtocol.CreateFragments(media, true)))
}
