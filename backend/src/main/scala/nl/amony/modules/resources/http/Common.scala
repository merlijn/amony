package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.toTraverseOps
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.EndpointOutput
import sttp.tapir.EndpointOutput.OneOfVariant

import nl.amony.modules.resources.api.ResourceId

def oneOfList[T](variants: List[OneOfVariant[? <: T]]) = EndpointOutput.OneOf[T, T](variants, Mapping.id)

given Codec[String, ResourceId, TextPlain] = Codec.string.mapDecode(s => DecodeResult.Value(ResourceId.apply(s)))(identity)

enum ApiError:
  case NotFound, BadRequest

val apiErrorOutputs = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(ApiError.NotFound),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.BadRequest))(ApiError.BadRequest)
)

def sanitize(input: String, maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, String] =
  for
    _      <- EitherT.cond[IO](input.length <= maxLength, (), ApiError.BadRequest)
    _      <- EitherT.cond[IO](input.forall(characterAllowFn), (), ApiError.BadRequest)
    trimmed = input.trim
    _      <- EitherT.cond[IO](trimmed == Jsoup.clean(trimmed, Safelist.basic), (), ApiError.BadRequest)
  yield trimmed

def sanitizeOpt(input: Option[String], maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, Option[String]] =
  input.map(sanitize(_, maxLength, characterAllowFn).map(Some(_))).getOrElse(EitherT.rightT[IO, ApiError](None))

def sanitizeTags(tags: List[String]): EitherT[IO, ApiError, List[String]] = tags.map(sanitize(_, 64, _.isLetterOrDigit)).sequence

val apiNoCacheHeaders: EndpointOutput[Unit] = List(
  header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
  header(HeaderNames.Pragma, "no-cache"),
  header(HeaderNames.Expires, "0")
).reduce(_ and _)
