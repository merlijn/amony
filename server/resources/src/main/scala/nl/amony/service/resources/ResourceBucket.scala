package nl.amony.service.resources

import cats.effect.IO

import scala.concurrent.Future


trait ResourceBucket {

  def getResource(resourceId: String): IO[Option[ResourceContent]]

  def getVideoTranscode(resourceId: String, scaleHeight: Int): IO[Option[ResourceContent]]

  def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Boolean]

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): IO[Option[ResourceContent]]

  def getPreviewSpriteVtt(resourceId: String): IO[Option[String]]

  def getPreviewSpriteImage(resourceId: String): IO[Option[ResourceContent]]

  def getVideoThumbnail(resourceId: String, quality: Int, timestamp: Long): IO[Option[ResourceContent]]

  def getImageThumbnail(resourceId: String, scaleHeight: Int): IO[Option[ResourceContent]]
}
