package nl.amony.modules.resources.api

import io.circe.Codec as CirceCodec

/** Identifies a file staged in a bucket's temp directory while an upload is being prepared. */
opaque type StagingId <: String = String

object StagingId:
  def apply(id: String): StagingId = id

  given circeCodec: CirceCodec[StagingId] = CirceCodec.implied[String]
