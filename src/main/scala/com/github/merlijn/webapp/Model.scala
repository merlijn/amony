package com.github.merlijn.webapp

import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._

object Model {

  case class Video(id: String,
                   fileName: String,
                   title: String,
                   duration: Long,
                   thumbnail: String,
                   resolution: String,
                   tags: Seq[String])

  case class SearchResult(
      currentPage: Int,
      pageSize: Int,
      total: Int,
      videos: List[Video]
  )

  implicit val videoCodec: Codec[Video] = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult] = deriveCodec[SearchResult]
}