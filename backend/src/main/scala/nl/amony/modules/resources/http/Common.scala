package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.HeaderNames
import sttp.tapir.*
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.EndpointOutput

import nl.amony.modules.resources.api.ResourceId

def sanitize(input: String, maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, String] =
  for
    _      <- EitherT.cond[IO](input.length <= maxLength, (), ApiError.BadRequest)
    _      <- EitherT.cond[IO](input.forall(characterAllowFn), (), ApiError.BadRequest)
    trimmed = input.trim
    _      <- EitherT.cond[IO](trimmed == Jsoup.clean(trimmed, Safelist.basic), (), ApiError.BadRequest)
  yield trimmed

val apiNoCacheHeaders: EndpointOutput[Unit] = List(
  header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
  header(HeaderNames.Pragma, "no-cache"),
  header(HeaderNames.Expires, "0")
).reduce(_ and _)
