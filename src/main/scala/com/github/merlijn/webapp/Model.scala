package com.github.merlijn.webapp

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.parser.*
import io.circe.syntax.*

object Model {

  case class Video(id: String,
                   title: String, 
                   thumbnail: String,
                   tags: Seq[String])

  implicit val videoEncoder: Encoder[Video] = deriveEncoder[Video]
}