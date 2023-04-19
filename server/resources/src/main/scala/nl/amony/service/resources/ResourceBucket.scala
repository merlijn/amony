package nl.amony.service.resources

import cats.effect.IO

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

  def getResource(resourceId: String): IO[Option[IOResponse]]

  def getVideoTranscode(resourceId: String, scaleHeight: Int): IO[Option[IOResponse]]

  def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Boolean]

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): IO[Option[IOResponse]]

  def getPreviewSpriteVtt(resourceId: String): IO[Option[String]]

  def getPreviewSpriteImage(resourceId: String): IO[Option[IOResponse]]

  def getVideoThumbnail(resourceId: String, quality: Int, timestamp: Long): IO[Option[IOResponse]]

  def getImageThumbnail(resourceId: String, scaleHeight: Int): IO[Option[IOResponse]]
}
