package nl.amony.service.resources

import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.Future

trait ResourceBucket {

  def getResource(resourceId: String, quality: Int): Future[Option[IOResponse]]

  def uploadResource(fileName: String, source: Source[ByteString, Any]): Future[Boolean]

  def getVideoFragment(resourceId: String, start: Long, end: Long, quality: Int): Future[Option[IOResponse]]

  def getPreviewSpriteVtt(resourceId: String): Future[Option[String]]

  def getPreviewSpriteImage(resourceId: String): Future[Option[IOResponse]]

  def getThumbnail(resourceId: String, quality: Int, timestamp: Long): Future[Option[IOResponse]]
}
