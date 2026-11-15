package nl.amony.modules.resources.api

import cats.effect.IO

import nl.amony.modules.auth.api.{Role, UserId}

enum UploadError:
  case InvalidFileName(message: String)
  case StorageError(message: String)

enum ResourceJobError:
  case NotFound
  case Rejected(message: String)

enum StreamabilityError:
  case ResourceNotFound(resourceId: ResourceId)
  case UnsupportedContainer(contentType: String)
  case FixFailed(path: String, reason: String)

  def message: String = this match
    case ResourceNotFound(resourceId)      => s"Failed to make '$resourceId' streamable: resource no longer exists"
    case UnsupportedContainer(contentType) => s"Cannot remux content type '$contentType' for streaming"
    case FixFailed(path, reason)           => s"Failed to make '$path' streamable: $reason"

/** An upload that has been validated, optionally remuxed and scanned, ready to be moved into the resource directory. */
final case class PreparedUpload(tempFile: String, partialHash: String, contentMeta: ContentMeta)

final case class UploadProcessingError(tempFile: String, message: String)

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

  def deleteFile(info: ResourceInfo): IO[Unit]

  /**
   * Performs an operation on a resource and returns the resulting content
   */
  def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]]

  def generateAllPreviews(): IO[Unit]

  def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit]

  def refresh(): IO[Unit]

  def reScanAllMetadata(): IO[Unit]

  def reComputePartialHashes(): IO[Unit]

  def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit]

  def fixStreamability(resourceId: ResourceId): IO[Either[ResourceJobError, Unit]]

  def remuxForStreaming(resourceId: ResourceId): IO[Either[StreamabilityError, Option[String]]]

  def commitStreamabilityFix(resourceId: ResourceId, tempFile: Option[String]): IO[Either[StreamabilityError, Unit]]

  def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, ResourceInfo]]

  def prepareUpload(upload: UploadResource): IO[Either[UploadProcessingError, PreparedUpload]]

  def commitUpload(upload: UploadResource, prepared: PreparedUpload): IO[Either[UploadProcessingError, Unit]]

  def getAllResources: fs2.Stream[IO, ResourceInfo]
}
