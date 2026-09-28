package nl.amony.modules.resources.api

import java.util.UUID

import sttp.tapir.{DecodeResult, Schema}

opaque type CollectionId <: UUID = UUID

object CollectionId:
  def apply(id: UUID): CollectionId = id

  extension (id: CollectionId)
    def value: UUID = id

  given schema: Schema[CollectionId] = Schema.schemaForUUID

  given stringCodec: sttp.tapir.Codec[String, CollectionId, sttp.tapir.CodecFormat.TextPlain] =
    sttp.tapir.Codec.uuid.mapDecode(uuid => DecodeResult.Value(CollectionId(uuid)))(_.value)
