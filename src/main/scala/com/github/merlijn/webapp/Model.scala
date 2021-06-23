package com.github.merlijn.webapp

import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._

object Model {

  case class Video(id: String,
                   title: String, 
                   thumbnail: String,
                   tags: Seq[String])

  implicit val videoEncoder: Encoder[Video] = deriveEncoder[Video]
}