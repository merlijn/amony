package nl.amony.service.search.domain

import nl.amony.service.resources.domain.ResourceInfo

enum SortField:
  case Title, DateAdded, Duration, Size

enum SortDirection:
  case Asc, Desc

case class SortOption(field: SortField, direction: SortDirection)

case class Query(
  q: Option[String]         = None,
  parentId: Option[String]  = None,
  n: Int,
  offset: Option[Int]       = None,
  tags: List[String]        = List.empty,
  playlist: Option[String]  = None,
  minRes: Option[Int]       = None,
  maxRes: Option[Int]       = None,
  minDuration: Option[Long] = None,
  maxDuration: Option[Long] = None,
  sort: Option[SortOption]  = None,
  untagged: Option[Boolean] = None
)

case class SearchResult(offset: Int, total: Int, results: List[ResourceInfo], tags: Map[String, Long])
