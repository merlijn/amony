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

  implicit val videoEncoder: Codec[Video] = deriveCodec[Video]
}