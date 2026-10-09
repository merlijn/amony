package nl.amony.lib.files

import java.nio.file.{FileSystems, Path, PathMatcher}
import java.util.Locale
import scala.util.Try

/**
 * Case-insensitive glob patterns (java.nio PathMatcher syntax), matched against paths relative to a root directory
 * using '/' as separator. A leading '/' is ignored and a pattern starting with `**` + '/' also matches at the root.
 */
class GlobPatterns private (val patterns: List[String], matchers: List[PathMatcher]):

  def matches(relativePath: String): Boolean =
    val normalized = Path.of(relativePath.replace('\\', '/').toLowerCase(Locale.ROOT))
    matchers.exists(_.matches(normalized))

object GlobPatterns:

  private val anyDirectoryPrefix = "**/"

  private def compile(pattern: String): PathMatcher =
    FileSystems.getDefault.getPathMatcher(s"glob:$pattern")

  private def matchersFor(pattern: String): Either[String, List[PathMatcher]] =
    val normalized = pattern.strip().stripPrefix("/").toLowerCase(Locale.ROOT)
    val variants   =
      if normalized.startsWith(anyDirectoryPrefix) then List(normalized, normalized.drop(anyDirectoryPrefix.length)) else List(normalized)

    if normalized.isEmpty then Left("Patterns cannot be empty")
    else Try(variants.map(compile)).toEither.left.map(_ => s"Invalid pattern: '$pattern'")

  def parse(patterns: List[String]): Either[String, GlobPatterns] =
    patterns.foldLeft[Either[String, List[PathMatcher]]](Right(Nil)) { (acc, pattern) =>
      acc.flatMap(matchers => matchersFor(pattern).map(matchers ++ _))
    }.map(GlobPatterns(patterns, _))

  def unsafeParse(patterns: List[String]): GlobPatterns =
    parse(patterns).fold(error => throw new IllegalArgumentException(error), identity)
