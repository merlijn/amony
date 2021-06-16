package com.github.merlijn.webapp

object Model {

  import io.circe.*
  import io.circe.generic.auto.*
  import io.circe.parser.*
  import io.circe.syntax.*

  case class Movie(title: String, thumbnail: String, id: String)

  val foo = Movie("foo", "bar", "1")

  val json = foo.asJson.noSpaces
  println(json)
}