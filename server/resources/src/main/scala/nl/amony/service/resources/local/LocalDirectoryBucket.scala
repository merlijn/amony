package nl.amony.service.resources.local

import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.{ByteString, Timeout}
import cats.effect.unsafe.IORuntime
import nl.amony.lib.config.ConfigHelper
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.ProbeOutput
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.local.DirectoryScanner.LocalFile
import nl.amony.service.resources.local.LocalResourcesStore._
import nl.amony.service.resources.{IOResponse, ResourceBucket}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

sealed trait ResourceKey

case class ThumbnailKey(resourceId: String, timestamp: Long, quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}-${timestamp}_${quality}p.webp"
}
case class FragmentKey(resourceId: String, range: (Long, Long), quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}-${range._1}-${range._2}_${quality}p.mp4"
}

class LocalDirectoryBucket(system: ActorSystem[Nothing]) extends ResourceBucket {

  import pureconfig.generic.auto._
  val config = ConfigHelper.loadConfig[LocalResourcesConfig](system.settings.config, "amony.media")

  private val timeout = 5.seconds
  private val resourceStore = new ConcurrentHashMap[ResourceKey, Path]()

  import akka.actor.typed.scaladsl.AskPattern.Askable

  implicit def askTimeout: Timeout = Timeout(5.seconds)

  implicit val ec: ExecutionContext = system.executionContext
  implicit val mat: Materializer = SystemMaterializer.get(system).materializer
  implicit val scheduler: Scheduler = system.scheduler

  def ask[S: ServiceKey, Res](replyTo: ActorRef[Res] => S) = {

    val serviceRef = system.receptionist
      .ask[Receptionist.Listing](ref => Find(implicitly[ServiceKey[S]], ref))
      .map(_.serviceInstances(implicitly[ServiceKey[S]]).head)

    serviceRef.flatMap(_.ask(replyTo))
  }

  // TODO think about replacing this with custom runtime
  implicit val runtime: IORuntime = IORuntime.global

  def uploadResource(fileName: String, source: Source[ByteString, Any]): Future[Boolean] =
    ask[LocalResourceCommand, Boolean](ref => Upload(fileName, source.runWith(StreamRefs.sourceRef()), ref))

  override def getResource(resourceId: String, quality: Int): Future[Option[IOResponse]] =
    ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(resourceId, ref))
      .map(_.flatMap(f => LocalFileIOResponse.option(config.mediaPath.resolve(f.relativePath))))

  override def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]] = {

    val key = FragmentKey(resourceId, (start, end), quality)
    val path = resourceStore.compute(key, (_, value) => getOrCreateVideoFragment(key))

    Future.successful(LocalFileIOResponse.option(path))
  }

  private def getFileInfo(resourceId: String): Future[Option[LocalFile]] =
    ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(resourceId, ref))

  private def getOrCreateVideoFragment(key: FragmentKey): Path = {

    val fragmentPath = config.resourcePath.resolve(key.path)

    if (fragmentPath.exists())
      fragmentPath
    else {
      val resourceInfo = Await.result(getFileInfo(key.resourceId), timeout)

      resourceInfo.foreach { info =>
        FFMpeg.transcodeToMp4(
          inputFile   = config.mediaPath.resolve(info.relativePath),
          range       = key.range,
          crf         = 23,
          scaleHeight = Some(key.quality),
          outputFile  = Some(fragmentPath),
        ).unsafeRunSync()
      }

      fragmentPath
    }
  }

  private def getOrCreateThumbnail(key: ThumbnailKey): Path = {
    val thumbnailPath = config.resourcePath.resolve(key.path)

    if (thumbnailPath.exists())
      thumbnailPath
    else {
      val resourceInfo = Await.result(getFileInfo(key.resourceId), timeout)

      resourceInfo.foreach { info =>
        FFMpeg.createThumbnail(
          inputFile   = config.mediaPath.resolve(info.relativePath),
          timestamp   = key.timestamp,
          outputFile  = Some(thumbnailPath),
          scaleHeight = Some(key.quality)
        ).unsafeRunSync()
      }

      thumbnailPath
    }
  }

  override def getThumbnail(resourceId: String, quality: Int, timestamp: Long): Future[Option[IOResponse]] = {

    val key = ThumbnailKey(resourceId, timestamp, quality)
    val path = resourceStore.compute(key, (_, value) => getOrCreateThumbnail(key))

    Future.successful(LocalFileIOResponse.option(path))
  }

  override def getPreviewSpriteVtt(resourceId: String): Future[Option[String]] = {

    val path = config.resourcePath.resolve(s"$resourceId-timeline.vtt")

    val content =
      if (Files.exists(path))
        scala.io.Source.fromFile(path.toFile).mkString
      else
        "WEBVTT"

    Future.successful(Some(content))
  }

  override def getPreviewSpriteImage(mediaId: String): Future[Option[IOResponse]] = {
    val path = config.resourcePath.resolve(s"$mediaId-timeline.webp")
    Future.successful(LocalFileIOResponse.option(path))
  }

  override def getFFProbeOutput(resourceId: String): Future[Option[ProbeOutput]] = {

    getFileInfo(resourceId).flatMap {
      case None       => Future.successful(None)
      case Some(info) =>
        val path = config.mediaPath.resolve(info.relativePath)
        FFMpeg.ffprobe(path, false).unsafeToFuture().map(Some(_))
    }
  }
}
