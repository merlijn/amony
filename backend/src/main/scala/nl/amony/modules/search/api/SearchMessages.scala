package nl.amony.modules.search.api

import nl.amony.modules.resources.api.ResourceInfo

enum SortField:
  case Title, DateAdded, Duration, Size
  case Random(seed: Int)

enum SortDirection:
  case Asc, Desc

case class SortOption(field: SortField, direction: SortDirection)
case class DurationRange(min: Option[Long], max: Option[Long])
case class ResolutionRange(min: Option[Int], max: Option[Int])
case class UploadDateRange(min: Option[Long], max: Option[Long])

case class Query(
  q: Option[String]                = None,
  parentId: Option[String]         = None,
  n: Int,
  offset: Option[Int]              = None,
  includeTags: Set[String]         = Set.empty,
  excludeTags: Set[String]         = Set.empty,
  excludeBuckets: Set[String]      = Set.empty,
  resolutionRange: ResolutionRange = ResolutionRange(None, None),
  durationRange: DurationRange     = DurationRange(None, None),
  uploadDateRange: UploadDateRange = UploadDateRange(None, None),
  sort: Option[SortOption]         = None,
  untagged: Option[Boolean]        = None
)

case class SearchResult(offset: Int, total: Int, results: List[ResourceInfo], tags: Map[String, Long])
