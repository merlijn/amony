package nl.amony.modules.resources.api

sealed trait ResourceEvent derives io.circe.Codec:
  def messageKey: String

case class ResourceAdded(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"${resource.bucketId}:${resource.resourceId}"

case class ResourceDeleted(bucketId: BucketId, resourceId: ResourceId) extends ResourceEvent derives io.circe.Codec:
  override def messageKey: String = s"$bucketId:$resourceId"

case class ResourceUpdated(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec:
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
