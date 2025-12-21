package nl.amony.lib.auth

import io.circe.Codec

opaque type Role <: String = String

object Role:
  def apply(d: String): Role            = d
  given codec: Codec[Role]              = new io.circe.Codec[String]:
    def apply(a: Role): io.circe.Json                             = io.circe.Json.fromString(a)
    def apply(c: io.circe.HCursor): io.circe.Decoder.Result[Role] = c.as[String].map(Role(_))
  given schema: sttp.tapir.Schema[Role] = sttp.tapir.Schema.string

extension (role: Role) def value: String = role

object Roles:
  val Admin = Role("admin")
