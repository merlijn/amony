package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.ProbeOutput
import nl.amony.lib.magick.tasks.ImageMagickModel.{ImageMeta, MagickImageMeta}

import scala.concurrent.Future

// TODO GRPC
trait ResourceBucket {

  def getResource(resourceId: String): Future[Option[IOResponse]]

  def getVideo(resourceId: String, scaleHeight: Int): Future[Option[IOResponse]]

  def getFFProbeOutput(resourceId: String): Future[Option[ProbeOutput]]

  def getImageMetaData(resourceId: String): Future[Option[ImageMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): Future[Boolean]

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]]

  def getPreviewSpriteVtt(resourceId: String): Future[Option[String]]

  def getPreviewSpriteImage(resourceId: String): Future[Option[IOResponse]]

  def getThumbnail(resourceId: String, quality: Int, timestamp: Long): Future[Option[IOResponse]]

  def getImageThumbnail(resourceId: String, scaleHeight: Int): Future[Option[IOResponse]]
}
