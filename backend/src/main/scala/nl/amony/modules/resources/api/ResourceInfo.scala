package nl.amony.modules.resources.api

case class ResourceInfo(
  bucketId: String,
  resourceId: String,
  userId: String,
  path: String,
  size: Long,
  hash: Option[String]              = None,
  contentType: Option[String]       = None,
  contentMeta: Option[ResourceMeta] = None,
  timeAdded: Option[Long]           = None,
  timeCreated: Option[Long]         = None,
  timeLastModified: Option[Long]    = None,
  title: Option[String]             = None,
  description: Option[String]       = None,
  tags: Set[String]                 = Set.empty,
  thumbnailTimestamp: Option[Int]   = None
)
