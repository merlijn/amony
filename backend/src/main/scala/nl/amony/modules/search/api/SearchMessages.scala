package nl.amony.modules.search.api

import nl.amony.modules.resources.api.{BucketId, ResourceInfo}

enum SortField:
  case Title, DateAdded, Duration, Size
  case Random(seed: Int)

enum SortDirection:
  case Asc, Desc

enum ContentTypeGroup:
  case Image, Video, Other

object ContentTypeGroup:
  def fromString(value: String): Option[ContentTypeGroup] = value.trim.toLowerCase match
    case "image" => Some(Image)
    case "video" => Some(Video)
    case "other" => Some(Other)
    case _       => None

case class SortOption(field: SortField, direction: SortDirection)
case class DurationRange(min: Option[Long], max: Option[Long])
case class ResolutionRange(min: Option[Int], max: Option[Int])
case class UploadDateRange(min: Option[Long], max: Option[Long])

case class Query(
  q: Option[String]                   = None,
  parentId: Option[String]            = None,
  n: Int,
  offset: Option[Int]                 = None,
  includeTags: Set[String]            = Set.empty,
  excludeBuckets: Set[BucketId]       = Set.empty,
  includeBuckets: Set[BucketId]       = Set.empty,
  resolutionRange: ResolutionRange    = ResolutionRange(None, None),
  durationRange: DurationRange        = DurationRange(None, None),
  uploadDateRange: UploadDateRange    = UploadDateRange(None, None),
  sort: Option[SortOption]            = None,
  untagged: Option[Boolean]           = None,
  streamable: Option[Boolean]         = None,
  noThumbnail: Option[Boolean]        = None,
  contentTypes: Set[ContentTypeGroup] = Set.empty
)

case class SearchResult(offset: Int, total: Int, results: List[ResourceInfo], tags: Map[String, Long])
