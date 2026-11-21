package nl.amony.modules.resources.api

import io.circe.Codec as CirceCodec

/** Path of a resource relative to the root of its bucket. */
opaque type ResourcePath <: String = String

object ResourcePath:
  def apply(path: String): ResourcePath = path

  /**
   * Validates an untrusted string as a bucket-relative path. Rejects blank, absolute and bucket-escaping paths so that
   * resolving the result against the bucket root cannot leave the bucket.
   */
  def fromString(path: String): Either[String, ResourcePath] =
    if path.isBlank then Left("Resource path must not be blank")
    else if path.startsWith("/") then Left(s"Resource path must be relative: $path")
    else if path.split("/", -1).contains("..") then Left(s"Resource path must not escape the bucket: $path")
    else Right(path)

  given circeCodec: CirceCodec[ResourcePath] = CirceCodec.implied[String]
