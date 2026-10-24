package nl.amony.modules.auth.api

import io.circe.Codec

opaque type UserId <: String = String

object UserId:
  def apply(id: String): UserId = id

  val superUser = UserId("superuser")
  val anonymous = UserId("anonymous")

  given schema: sttp.tapir.Schema[UserId] = sttp.tapir.Schema.string
  given codec: Codec[UserId]              = Codec.implied[String]
