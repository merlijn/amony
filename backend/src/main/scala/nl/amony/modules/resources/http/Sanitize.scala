package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.toTraverseOps
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

import nl.amony.lib.tapir.dsl.error.BadRequestError

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
