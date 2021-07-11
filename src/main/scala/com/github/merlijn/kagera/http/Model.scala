package com.github.merlijn.kagera.http

object Model {

  case class Thumbnail(
      timestamp: Long,
      uri: String
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      thumbnail: Thumbnail,
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
