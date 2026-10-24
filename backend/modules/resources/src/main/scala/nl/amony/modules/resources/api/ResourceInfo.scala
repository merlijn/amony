package nl.amony.modules.resources.api

import nl.amony.modules.auth.api.UserId

case class ResourceInfo(
  bucketId: BucketId,
  resourceId: ResourceId,
  userId: UserId,
  path: String,
  size: Long,
  partialHash: Option[String]     = None,
  fileKey: Option[String]         = None,
  contentMeta: ResourceMeta       = ResourceMeta.empty,
  timeAdded: Option[Long]         = None,
  timeLastModified: Option[Long]  = None,
  title: Option[String]           = None,
  description: Option[String]     = None,
  tags: Set[Tag]                  = Set.empty,
  thumbnailTimestamp: Option[Int] = None
) derives io.circe.Codec {

  def contentType: Option[String] = contentMeta.contentType

  def streamable: Option[Boolean] = contentMeta.streamable

  def basicContentProperties: Option[ContentProperties] = contentMeta.properties

  def userLabels: Set[String] = tags.collect { case Tag(owner, label) if owner == TagOwner.User => label }

  def labelsByOwner: Map[String, Set[String]] = tags.groupBy(_.owner).view.mapValues(_.map(_.label).toSet).toMap

  def withTagsForOwner(owner: String, labels: Set[String]): ResourceInfo =
    copy(tags = tags.filterNot(_.owner == owner) ++ labels.map(Tag(owner, _)))
}
