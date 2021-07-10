package com.github.merlijn.kagera.http

import java.nio.file.Path

object Model {

  case class ThumbNail(
      timestamp: Long,
      uri: String
  )

  case class Video(
      id: String,
      fileName: String,
      title: String,
      duration: Long,
      thumbnail: ThumbNail,
      resolution: String,
      tags: Seq[String]
  ) {

    def path(baseDir: Path): Path = baseDir.resolve(fileName)

    def thumbnailPath(baseDir: Path) = {
      baseDir.resolve(thumbnail.uri.substring(thumbnail.uri.lastIndexOf('/') + 1))
    }
  }

  case class SearchResult(
      currentPage: Int,
      offset: Int,
      total: Int,
      videos: Seq[Video]
  )

  case class Collection(
      id: Int,
      name: String
  )
}
