package nl.amony.modules.resources.api

import nl.amony.modules.auth.api.UserId

case class ResourceInfo(
  bucketId: String,
  resourceId: ResourceId,
  userId: UserId,
  path: String,
  size: Long,
  hash: Option[String]              = None,
  contentType: Option[String]       = None,
  contentMeta: Option[ResourceMeta] = None,
  timeAdded: Option[Long]           = None,
  timeLastModified: Option[Long]    = None,
  title: Option[String]             = None,
  description: Option[String]       = None,
  tags: Set[String]                 = Set.empty,
  thumbnailTimestamp: Option[Int]   = None
)
