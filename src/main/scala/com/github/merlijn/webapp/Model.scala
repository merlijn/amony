package com.github.merlijn.webapp

object Model {

  import io.circe.*
  import io.circe.generic.semiauto.*
  import io.circe.parser.*
  import io.circe.syntax.*

  case class Video(title: String, thumbnail: String, id: String)

  implicit val videoEncoder: Encoder[Video] = deriveEncoder[Video]
}