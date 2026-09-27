package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.toTraverseOps
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.HeaderNames
import sttp.tapir.*
import sttp.tapir.CodecFormat.TextPlain

import nl.amony.lib.tapir.dsl.error.BadRequestError
import nl.amony.modules.resources.api.ResourceId

given Codec[String, ResourceId, TextPlain] = Codec.string.mapDecode(s => DecodeResult.Value(ResourceId.apply(s)))(identity)

def sanitize(input: String, maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, BadRequestError, String] =
  for
    _      <- EitherT.cond[IO](input.length <= maxLength, (), BadRequestError("too_long", s"Value must be at most $maxLength characters"))
    _      <- EitherT.cond[IO](input.forall(characterAllowFn), (), BadRequestError("invalid_characters", "Value contains invalid characters"))
    trimmed = input.trim
    _      <- EitherT.cond[IO](trimmed == Jsoup.clean(trimmed, Safelist.basic), (), BadRequestError("invalid_markup", "Value contains invalid markup"))
  yield trimmed

def sanitizeOpt(input: Option[String], maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, BadRequestError, Option[String]] =
  input.map(s => sanitize(s, maxLength, characterAllowFn).map(Some(_))).getOrElse(EitherT.rightT[IO, BadRequestError](None))

def sanitizeTags(tags: List[String]): EitherT[IO, BadRequestError, List[String]] = tags.map(sanitize(_, 64, _.isLetterOrDigit)).sequence

val apiNoCacheHeaders: EndpointOutput[Unit] = List(
  header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
  header(HeaderNames.Pragma, "no-cache"),
  header(HeaderNames.Expires, "0")
).reduce(_ and _)
