package nl.amony.service.resources

import cats.effect.IO
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.api.operations.ResourceOperation

trait ResourceBucket {

  /**
   * Returns the content of a resource
   */
  def getResource(resourceId: String): IO[Option[Resource]]
  
  def updateUserMeta(resourceId: String, title: Option[String], description: Option[String], tags: List[String]): IO[Unit]

  def deleteResource(resourceId: String): IO[Unit]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[Resource]]

  def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo]
}
