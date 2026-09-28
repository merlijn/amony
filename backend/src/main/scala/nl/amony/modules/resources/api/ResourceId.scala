package nl.amony.modules.resources.api

import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec, DecodeResult}

opaque type ResourceId <: String = String

object ResourceId:
  def apply(id: String): ResourceId = id

  given Codec[String, ResourceId, TextPlain] = Codec.string.mapDecode(s => DecodeResult.Value(ResourceId.apply(s)))(identity)
