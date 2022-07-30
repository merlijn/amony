package nl.amony.service.resources

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.ByteString
import monix.execution.Scheduler
import nl.amony.lib.akka.{AkkaServiceModule, ServiceBehaviors}
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.actor.MediaLibProtocol.{GetById, Media, MediaCommand}
import nl.amony.service.resources.ResourceProtocol._
import nl.amony.service.resources.local.LocalResourcesStore.{FullScan, GetByHash, LocalFile, LocalResourceCommand, Upload}
import nl.amony.service.resources.local.{LocalFileIOResponse, LocalResourcesHandler}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object ResourceService {

  def behavior(config: LocalResourcesConfig, storeRef: ActorRef[LocalResourceCommand]): Behavior[ResourceCommand] = {

    ServiceBehaviors.setupAndRegister[ResourceCommand] { context =>
      storeRef.tell(FullScan(context.system.ignoreRef))
      LocalResourcesHandler.apply(config, storeRef)
    }
  }
}

sealed trait ResourceKey

case class ThumbnailKey(resourceId: String, timestamp: Long, quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}-${timestamp}_${quality}p.webp"
}
case class FragmentKey(resourceId: String, range: (Long, Long), quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}-${range._1}-${range._2}_${quality}p.mp4"
}

class ResourceService(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) {

  import pureconfig.generic.auto._
  val config = loadConfig[LocalResourcesConfig]("amony.media")

  val timeout = 5.seconds
  val resourceStore = new ConcurrentHashMap[ResourceKey, Path]()

  implicit val monixScheduler: Scheduler = Scheduler.forkJoin(5, 256)

  def uploadResource(bucketId: String, fileName: String, source: Source[ByteString, Any]): Future[Boolean] =
    ask[LocalResourceCommand, Boolean](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))

  def getResource(bucketId: String, resourceId: String, quality: Int): Future[Option[IOResponse]] =
    ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(resourceId, ref))
      .map(_.flatMap(f => LocalFileIOResponse.option(config.mediaPath.resolve(f.relativePath))))

  def getVideoFragment(bucketId: String, resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]] = {

    val key = FragmentKey(resourceId, (start, end), quality)
    val path = resourceStore.compute(key, (_, value) => getOrCreateVideoFragment(key))

    Future.successful(LocalFileIOResponse.option(path))
  }

  def getResourceInfo(bucketId: String, resourceId: String): Future[Option[ResourceInfo]] = ???

  def getOrCreateVideoFragment(key: FragmentKey): Path = {

    val fragmentPath = config.resourcePath.resolve(key.path)

    if (fragmentPath.exists())
      fragmentPath
    else {
      val resourceInfo =
        Await.result(ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(key.resourceId, ref)), timeout)

      resourceInfo.foreach { info =>
        FFMpeg.transcodeToMp4(
          inputFile   = config.mediaPath.resolve(info.relativePath),
          range       = key.range,
          outputFile  = Some(fragmentPath),
          quality     = 23,
          scaleHeight = Some(key.quality)
        ).runSyncUnsafe()
      }

      fragmentPath
    }
  }

  def getOrCreateThumbnail(key: ThumbnailKey): Path = {
    val thumbnailPath = config.resourcePath.resolve(key.path)

    if (thumbnailPath.exists())
      thumbnailPath
    else {
      val resourceInfo =
        Await.result(ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(key.resourceId, ref)), timeout)

      resourceInfo.foreach { info =>
        FFMpeg.createThumbnail(
          inputFile   = config.mediaPath.resolve(info.relativePath),
          timestamp   = key.timestamp,
          outputFile  = Some(thumbnailPath),
          scaleHeight = Some(key.quality)
        ).runSyncUnsafe()
      }

      thumbnailPath
    }
  }

  def getThumbnail(bucketId: String, resourceId: String, quality: Int, timestamp: Option[Long]): Future[Option[IOResponse]] = {

    ask[MediaCommand, Option[Media]](ref => GetById(resourceId, ref)).map { media =>
      timestamp.orElse(media.map(_.thumbnailTimestamp)).flatMap { t =>

        val key = ThumbnailKey(resourceId, t, quality)
        val path = resourceStore.compute(key, (_, value) => getOrCreateThumbnail(key))

        LocalFileIOResponse.option(path)
      }
    }
  }

  def getPreviewSpriteVtt(bucketId: String, resourceId: String): Future[Option[String]] = {

    val path = config.resourcePath.resolve(s"$resourceId-timeline.vtt")

    val content =
      if (Files.exists(path))
        scala.io.Source.fromFile(path.toFile).mkString
      else
        "WEBVTT"

    Future.successful(Some(content))
  }

  def getPreviewSpriteImage(bucketId: String, mediaId: String): Future[Option[IOResponse]] = {
    val path = config.resourcePath.resolve(s"$mediaId-timeline.webp")
    Future.successful(LocalFileIOResponse.option(path))
  }
}
