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

  /**
   * Returns the content of a derived resource identified by the operation id.
   *
   * The operation id serves as a pointer, meaning that the operation and resulting content may change.
   *
   * Example operation ids could be:
   *
   *   - primary_thumbnail
   *   - video_preview
   */
  def getOrCreate(resourceId: String, operationId: String): IO[Option[ResourceContent]]

  def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Boolean]
}
