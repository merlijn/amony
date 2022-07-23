package nl.amony.service.resources

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.ByteString
import nl.amony.lib.akka.{AkkaServiceModule, ServiceBehaviors}
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.actor.MediaLibProtocol.{GetById, Media, MediaCommand}
import nl.amony.service.resources.ResourceProtocol._
import nl.amony.service.resources.local.LocalResourcesStore.{FullScan, GetByHash, LocalFile, LocalResourceCommand, Upload}
import nl.amony.service.resources.local.{LocalFileIOResponse, LocalResourcesHandler}

import java.nio.file.Files
import scala.concurrent.Future

object ResourceService {

  def behavior(config: LocalResourcesConfig, storeRef: ActorRef[LocalResourceCommand]): Behavior[ResourceCommand] = {

    ServiceBehaviors.setupAndRegister[ResourceCommand] { context =>
      storeRef.tell(FullScan(context.system.ignoreRef))
      LocalResourcesHandler.apply(config, storeRef)
    }
  }
}

class ResourceService(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) {

  import pureconfig.generic.auto._
  val config = loadConfig[LocalResourcesConfig]("amony.media")

  def uploadResource(fileName: String, source: Source[ByteString, Any]): Future[Boolean] =
    ask[LocalResourceCommand, Boolean](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))

  def getResource(resourceId: String, quality: Int): Future[Option[IOResponse]] =
    ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(resourceId, ref))
      .map(_.flatMap(f => LocalFileIOResponse.option(config.mediaPath.resolve(f.relativePath))))

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]] = {
    val path = config.resourcePath.resolve(s"${resourceId}-${start}-${end}_${quality}p.mp4")
    Future.successful(LocalFileIOResponse.option(path))
  }

  def getThumbnail(resourceId: String, quality: Int, timestamp: Option[Long]): Future[Option[IOResponse]] = {

    ask[MediaCommand, Option[Media]](ref => GetById(resourceId, ref)).map { media =>
      timestamp.orElse(media.map(_.thumbnailTimestamp)).flatMap { t =>
        val path = config.resourcePath.resolve(s"${resourceId}-${t}_${quality}p.webp")
        LocalFileIOResponse.option(path)
      }
    }
  }

  def getPreviewSpriteVtt(resourceId: String): Future[Option[String]] = {

    val path = config.resourcePath.resolve(s"$resourceId-timeline.vtt")

    val content =
      if (Files.exists(path))
        scala.io.Source.fromFile(path.toFile).mkString
      else
        "WEBVTT"

    Future.successful(Some(content))
  }

  def getPreviewSpriteImage(mediaId: String): Future[Option[IOResponse]] = {
    val path = config.resourcePath.resolve(s"$mediaId-timeline.webp")
    Future.successful(LocalFileIOResponse.option(path))
  }
}
