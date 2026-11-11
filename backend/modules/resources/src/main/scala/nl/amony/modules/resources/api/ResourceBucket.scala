package nl.amony.modules.resources.api

import cats.effect.IO

import nl.amony.modules.auth.api.Role

enum UploadError:
  case InvalidFileName(message: String)
  case StorageError(message: String)

trait ResourceBucket {

  def id: BucketId

  /** When set, only users holding this role (admins always) can access the bucket. */
  def requiredRole: Option[Role]

  /**
   * Returns the content of a resource
  */
  def getResource(resourceId: ResourceId): IO[Option[Resource]]

  def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit]

  /**
   * Adds and removes tags for a given resource. Returns the updated resource info when the resource exists.
   */
  def updateResourceTags(resourceIds: Set[ResourceId], tagsToAdd: Set[Tag], tagsToRemove: Set[Tag]): IO[Unit]

  def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit]

  def deleteResource(resourceId: ResourceId): IO[Unit]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]]

  def getAllResources: fs2.Stream[IO, ResourceInfo]
}
