package io.amony.http

object Model {

  case class Preview(
      timestamp_start: Long,
      timestamp_end: Long,
      uri: String
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      fps: Double,
      thumbnail_uri: String,
      previews: List[Preview],
      resolution_x: Int,
      resolution_y: Int,
      tags: Seq[String]
  )

  case class SearchResult(
      offset: Int,
      total: Int,
      videos: Seq[Video]
  )

  case class Collection(
      id: String,
      title: String
  )
}
