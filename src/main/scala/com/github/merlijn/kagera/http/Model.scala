package com.github.merlijn.kagera.http

object Model {

  case class Preview(
      timestamp: Long,
      uri: String,
      webp_uri: String
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      thumbnail: Preview,
      resolution: String,
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
