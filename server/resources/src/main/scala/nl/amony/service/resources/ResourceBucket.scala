package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.ProbeOutput
import nl.amony.lib.magick.tasks.ImageMagickModel.{ImageMeta, MagickImageMeta}

import scala.concurrent.Future

sealed trait ResourceMeta {
  def contentType: String
}

case class VideoMeta(
    contentType: String,
    width: Int,
    height: Int,
    fps: Float,
    durationInMillis: Long,
    metaData: Map[String, String],
) extends ResourceMeta

case class ImageMeta(
    contentType: String,
    width: Int,
    height: Int,
    metaData: Map[String, String]
) extends ResourceMeta

case class Other(contentType: String) extends ResourceMeta

trait ResourceBucket {

  def getResource(resourceId: String): Future[Option[IOResponse]]

  def getVideo(resourceId: String, scaleHeight: Int): Future[Option[IOResponse]]

  def getResourceMeta(resourceId: String): Future[Option[ResourceMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): Future[Boolean]

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]]

  def getPreviewSpriteVtt(resourceId: String): Future[Option[String]]

  def getPreviewSpriteImage(resourceId: String): Future[Option[IOResponse]]

  def getVideoThumbnail(resourceId: String, quality: Int, timestamp: Long): Future[Option[IOResponse]]

  def getImageThumbnail(resourceId: String, scaleHeight: Int): Future[Option[IOResponse]]
}
