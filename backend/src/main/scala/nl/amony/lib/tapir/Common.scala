package nl.amony.lib.tapir

import sttp.model.HeaderNames
import sttp.tapir.{EndpointOutput, header}

val apiNoCacheHeaders: EndpointOutput[Unit] = List(
  header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
  header(HeaderNames.Pragma, "no-cache"),
  header(HeaderNames.Expires, "0")
).reduce(_ and _)