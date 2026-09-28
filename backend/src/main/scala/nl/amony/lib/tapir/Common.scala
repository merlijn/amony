package nl.amony.lib.tapir

import sttp.model.HeaderNames
import sttp.tapir.{EndpointOutput, Schema, header}

val apiNoCacheHeaders: EndpointOutput[Unit] = List(
  header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
  header(HeaderNames.Pragma, "no-cache"),
  header(HeaderNames.Expires, "0")
).reduce(_ and _)

/** Marks a field as required in the generated OpenAPI schema (tapir treats derived fields as optional by default). */
def required[T](s: Schema[T]) = s.copy(isOptional = false)
