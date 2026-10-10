package nl.amony.modules.resources.api

import io.circe.Codec as CirceCodec
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec, DecodeResult}

opaque type ResourceId <: String = String

object ResourceId:
  def apply(id: String): ResourceId = id

  given circeCodec: CirceCodec[ResourceId] = CirceCodec.implied[String]

  given Codec[String, ResourceId, TextPlain] = Codec.string.mapDecode(s => DecodeResult.Value(ResourceId.apply(s)))(identity)
