package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.ProbeOutput
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.events.Resource
import nl.amony.service.resources.{IOResponse, ResourceBucket}
import scribe.Logging
import slick.jdbc.JdbcProfile

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

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalResourcesConfig, repository: LocalDirectoryRepository[P])(implicit ec: ExecutionContext) extends ResourceBucket with Logging {

  private val timeout = 5.seconds
  private val resourceStore = new ConcurrentHashMap[ResourceKey, Path]()

  // TODO think about replacing this with custom runtime
  implicit val runtime: IORuntime = IORuntime.global

  override def getResource(resourceId: String, quality: Int): Future[Option[IOResponse]] = {
    repository.getByHash(resourceId)
      .map(_.flatMap(f => IOResponse.fromPath(config.mediaPath.resolve(f.path)))).unsafeToFuture()
  }

  override def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]] = {

    val key = FragmentKey(resourceId, (start, end), quality)
    val path = resourceStore.compute(key, (_, value) => getOrCreateVideoFragment(key))

    Future.successful(IOResponse.fromPath(path))
  }

  private def getFileInfo(resourceId: String): Future[Option[Resource]] =
    repository.getByHash(resourceId).unsafeToFuture()

  private def getOrCreateVideoFragment(key: FragmentKey): Path = {

    val fragmentPath = config.resourcePath.resolve(key.path)

    if (fragmentPath.exists())
      fragmentPath
    else {
      val resourceInfo = Await.result(getFileInfo(key.resourceId), timeout)

      resourceInfo.foreach { info =>
        FFMpeg.transcodeToMp4(
          inputFile   = config.mediaPath.resolve(info.path),
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
          inputFile   = config.mediaPath.resolve(info.path),
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

    Future.successful(IOResponse.fromPath(path))
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
    Future.successful(IOResponse.fromPath(path))
  }

  override def getFFProbeOutput(resourceId: String): Future[Option[ProbeOutput]] = {

    getFileInfo(resourceId).flatMap {
      case None       => Future.successful(None)
      case Some(info) =>
        val path = config.mediaPath.resolve(info.path)
        FFMpeg.ffprobe(path, false).unsafeToFuture().map(Some(_))
    }
  }

  override def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): Future[Boolean] = ???
}
