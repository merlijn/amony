package nl.amony.service.resources

import cats.effect.IO

trait ResourceBucket {

  /**
   * Returns the content of a resource
   */
  def getContent(resourceId: String): IO[Option[ResourceContent]]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[ResourceContent]]

  def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Boolean]
}
