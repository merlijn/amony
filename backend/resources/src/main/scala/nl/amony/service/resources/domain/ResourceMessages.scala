package nl.amony.service.resources.domain

case class ResourceMetaSource(
  toolName: String,
  toolData: String
)

sealed trait ResourceMeta

object ResourceMeta:
  case object Empty extends ResourceMeta

case class VideoMeta(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String] = None,
  metaData: Map[String, String] = Map.empty
) extends ResourceMeta

case class ImageMeta(
  width: Int,
  height: Int,
  metaData: Map[String, String] = Map.empty
) extends ResourceMeta

case class ResourceInfo(
  bucketId: String,
  resourceId: String,
  userId: String,
  path: String,
  size: Long,
  hash: Option[String] = None,
  contentType: Option[String] = None,
  contentMetaSource: Option[ResourceMetaSource] = None,
  contentMeta: ResourceMeta = ResourceMeta.Empty,
  timeAdded: Option[Long] = None,
  timeCreated: Option[Long] = None,
  timeLastModified: Option[Long] = None,
  title: Option[String] = None,
  description: Option[String] = None,
  tags: Set[String] = Set.empty,
  thumbnailTimestamp: Option[Int] = None
)
