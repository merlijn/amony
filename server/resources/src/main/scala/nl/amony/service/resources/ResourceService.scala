package nl.amony.service.resources

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.ByteString
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import ResourceConfig.LocalResourcesConfig
import cats.effect.unsafe.IORuntime
import nl.amony.service.resources.local.DirectoryScanner.LocalFile
import nl.amony.service.resources.local.LocalFileIOResponse
import nl.amony.service.resources.local.LocalResourcesStore._

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

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

  // TODO think about replacing this with custom runtime
  implicit val runtime: IORuntime = IORuntime.global

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

  private def getFileInfo(resourceId: String): Future[Option[LocalFile]] =
    ask[LocalResourceCommand, Option[LocalFile]](ref => GetByHash(resourceId, ref))

  def getOrCreateVideoFragment(key: FragmentKey): Path = {

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

  def getOrCreateThumbnail(key: ThumbnailKey): Path = {
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

  def getThumbnail(bucketId: String, resourceId: String, quality: Int, timestamp: Long): Future[Option[IOResponse]] = {

    val key = ThumbnailKey(resourceId, timestamp, quality)
    val path = resourceStore.compute(key, (_, value) => getOrCreateThumbnail(key))

    Future.successful(LocalFileIOResponse.option(path))
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
