package nl.amony.modules.auth.api

import io.circe.Codec

opaque type Role <: String = String

object Role:
  def apply(value: String): Role                    = value
  given schema: sttp.tapir.Schema[Role]             = sttp.tapir.Schema.string
  given codec: Codec[Role]                          = Codec.implied[String]
  given configReader: pureconfig.ConfigReader[Role] = pureconfig.ConfigReader.fromString[Role](str => Right(Role(str)))
  val Admin                                         = Role("admin")
  val Authenticated                                 = Role("authenticated")
  val Anonymous                                     = Role("anonymous")
