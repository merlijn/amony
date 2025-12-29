package nl.amony.modules.resources.domain

import cats.effect.IO

trait ResourceBucket {

  def id: String

  /**
   * Returns the content of a resource
  */
  def getResource(resourceId: String): IO[Option[Resource]]

  def updateUserMeta(resourceId: String, title: Option[String], description: Option[String], tags: List[String]): IO[Unit]

  /**
   * Adds and removes tags for a given resource. Returns the updated resource info when the resource exists.
   */
  def modifyTags(resourceIds: Set[String], tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit]

  def updateThumbnailTimestamp(resourceId: String, timestamp: Int): IO[Unit]

  def deleteResource(resourceId: String): IO[Unit]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[Resource]]

  def uploadResource(userId: String, fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo]

  def getAllResources: fs2.Stream[IO, ResourceInfo]
}
