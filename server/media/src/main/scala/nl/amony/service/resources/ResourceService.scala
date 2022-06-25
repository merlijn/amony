package nl.amony.service.resources

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.ByteString
import nl.amony.lib.akka.{AkkaServiceModule, ServiceBehaviors}
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.MediaService
import nl.amony.service.media.actor.MediaLibProtocol.Media
import nl.amony.service.resources.ResourceProtocol._
import nl.amony.service.resources.local.{LocalMediaScanner, LocalResourcesHandler}

import scala.concurrent.Future

object ResourceService {

  def behavior(config: LocalResourcesConfig, scanner: LocalMediaScanner): Behavior[ResourceCommand] = {

    ServiceBehaviors.setupAndRegister[ResourceCommand] { context =>

//      context.spawn(DirectoryWatcher.behavior(config), "directory-watcher")

      LocalResourcesHandler.apply(config, scanner)
    }
  }
}

class ResourceService(system: ActorSystem[Nothing], mediaApi: MediaService)
    extends AkkaServiceModule[ResourceCommand](system) {

  def uploadMedia(fileName: String, source: Source[ByteString, Any]): Future[Media] =
    askService[Media](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))
      .flatMap(mediaApi.upsertMedia)

  private def getResource(mediaId: String)(fn: (Media, ActorRef[IOResponse]) => ResourceCommand): Future[Option[IOResponse]] =
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

  def getPreviewSpriteVtt(mediaId: String): Future[Option[String]] =
    askService[Option[String]](ref => GetPreviewSpriteVtt(mediaId, ref))

  def getPreviewSpriteImage(mediaId: String): Future[Option[IOResponse]] =
    askService[Option[IOResponse]](ref => GetPreviewSpriteImage(mediaId, ref))

  def createFragments(media: Media): Unit =
    serviceRef().foreach(_.tell(ResourceProtocol.CreateFragments(media, true)))
}
