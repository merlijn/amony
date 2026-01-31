package nl.amony.modules.resources.api

import cats.effect.IO

import nl.amony.modules.auth.api.UserId

enum UploadError:
  case InvalidFileName(message: String)
  case StorageError(message: String)

trait ResourceBucket {

  def id: String

  /**
   * Returns the content of a resource
  */
  def getResource(resourceId: ResourceId): IO[Option[Resource]]

  def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit]

  /**
   * Adds and removes tags for a given resource. Returns the updated resource info when the resource exists.
   */
  def modifyTags(resourceIds: Set[ResourceId], tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit]

  def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit]

  def deleteResource(resourceId: ResourceId): IO[Unit]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]]

  /**
   * Uploads a resource to the bucket
   * 
   * @param userId
   * @param fileName
   * @param source
   * @return
   */
  def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, ResourceInfo]]

  def getAllResources: fs2.Stream[IO, ResourceInfo]
}
