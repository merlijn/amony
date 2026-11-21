package nl.amony.modules.resources.api

import io.circe.Codec as CirceCodec

/** Path of a resource relative to the root of its bucket. */
opaque type ResourcePath <: String = String

object ResourcePath:
  def apply(path: String): ResourcePath = path

  given circeCodec: CirceCodec[ResourcePath] = CirceCodec.implied[String]
