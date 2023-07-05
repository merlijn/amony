package nl.amony.service.resources

import cats.effect.IO
import nl.amony.service.resources.api.{Resource, ResourceMeta}
import nl.amony.service.resources.api.operations.ResourceOperation

trait ResourceBucket {

  /**
   * Returns the content of a resource
   */
  def getResource(resourceId: String): IO[Option[ResourceContent]]

  def deleteResource(resourceId: String): IO[Unit]

  def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: String, operation: ResourceOperation, tags: Set[String]): IO[Option[ResourceContent]]

  def getChildren(resourceId: String, tags: Set[String]): IO[Seq[(ResourceOperation, Resource)]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Resource]
}
