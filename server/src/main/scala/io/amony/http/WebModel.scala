package io.amony.http

object WebModel {

  case class FragmentRange(
      from: Long,
      to: Long
  )

  case class Fragment(
      index: Int,
      timestamp_start: Long,
      timestamp_end: Long,
      uri: String,
      comment: Option[String],
      tags: List[String]
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      fps: Double,
      thumbnail_uri: String,
      fragments: List[Fragment],
      resolution_x: Int,
      resolution_y: Int,
      tags: Seq[String]
  )

  case class SearchResult(
      offset: Int,
      total: Int,
      videos: Seq[Video]
  )

  case class Tag(
      id: String,
      title: String
  )
}
