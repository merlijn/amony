package nl.amony.modules.auth.api

import io.circe.Codec

opaque type Role <: String = String

object Role:
  def apply(d: String): Role            = d
  given schema: sttp.tapir.Schema[Role] = sttp.tapir.Schema.string
  given codec: Codec[Role]              = Codec.implied[String]

object Roles:
  val Admin = Role("admin")
