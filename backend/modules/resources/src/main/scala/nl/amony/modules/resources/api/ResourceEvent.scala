package nl.amony.modules.resources.api

sealed trait ResourceEvent derives io.circe.Codec:
  def messageKey: String

case class ResourceAdded(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceDeleted(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceModified(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceThumbnailUpdated(timestamp: Int, resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceUserMetaUpdated(
  resource: ResourceInfo,
  updatedDescription: Option[String],
  updatedTitle: Option[String],
  tagsAdded: Set[Tag],
  tagsRemoved: Set[Tag]
) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceFileMetaChanged(
  bucketId: BucketId,
  resourceId: ResourceId,
  lastModifiedTime: Long,
  fileKey: Option[String] = None
) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"$bucketId:$resourceId"

case class ResourceMoved(bucketId: BucketId, resourceId: ResourceId, oldPath: String, newPath: String) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"$bucketId:$resourceId"

case class ResourceMovedAndModified(resourceId: ResourceId, oldPath: String, newPath: String, resource: ResourceInfo)
    extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:$resourceId"

case class BucketDeleted(bucketId: BucketId) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = bucketId
