package nl.amony.modules.resources.api

import io.circe.Codec as CirceCodec
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec, DecodeResult}

opaque type BucketId <: String = String

object BucketId:
  def apply(id: String): BucketId = id

  given circeCodec: CirceCodec[BucketId] = CirceCodec.implied[String]

  given codec: Codec[String, BucketId, TextPlain] =
    Codec.string.mapDecode(s => DecodeResult.Value(BucketId(s)))(identity)
