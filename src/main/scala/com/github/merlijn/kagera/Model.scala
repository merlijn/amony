package com.github.merlijn.kagera

import io.circe._
import io.circe.generic.semiauto._

import java.nio.file.Path

object Model {

  case class Video(id: String,
                   fileName: String,
                   title: String,
                   duration: Long,
                   thumbnail: String,
                   resolution: String,
                   tags: Seq[String]) {

    def path(baseDir: Path): Path = baseDir.resolve(fileName)
    def thumbnailPath(baseDir: Path) = {
      baseDir.resolve(thumbnail.substring(thumbnail.lastIndexOf('/')+1) )
    }
  }

  case class SearchResult(
      currentPage: Int,
      pageSize: Int,
      total: Int,
      videos: Seq[Video]
  )

  case class Collection(
    id: Int,
    name: String
  )

  implicit val collectionCodec: Codec[Collection] = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video] = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult] = deriveCodec[SearchResult]
}