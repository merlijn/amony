package nl.amony.modules.resources.api

/** Which side of a thumbnail is pinned to a resolution; the other follows the source aspect ratio. */
enum ThumbnailDimension(val token: String, val name: String):
  case Width  extends ThumbnailDimension("w", "width")
  case Height extends ThumbnailDimension("h", "height")

object ThumbnailDimension:
  def fromToken(token: String): Option[ThumbnailDimension] = values.find(_.token == token)

/** A resolution key and the pixel size it pins for the chosen [[ThumbnailDimension]]. */
case class ThumbnailSize(key: String, pixels: Int)

/**
 * The fixed, configurable set of thumbnail resolutions.
 *
 * Keys are deliberately a small, closed set: clients pick a key and a dimension, never a pixel size,
 * so a client cannot make the server generate (and cache) arbitrary thumbnail sizes. Unknown keys
 * fall back to the default.
 */
final class ThumbnailResolutions(allowed: Map[String, Int], defaultKey: String):

  require(allowed.nonEmpty, "At least one thumbnail resolution must be configured")
  require(allowed.contains(defaultKey), s"Default thumbnail resolution '$defaultKey' is not in the configured resolutions: ${allowed.keys.mkString(", ")}")

  /** Resolutions ordered from smallest to largest. */
  val sizes: List[ThumbnailSize] = allowed.toList.sortBy(_._2).map((key, pixels) => ThumbnailSize(key, pixels))

  val dimensions: List[ThumbnailDimension] = ThumbnailDimension.values.toList

  val default: ThumbnailSize = sizes.find(_.key == defaultKey).getOrElse(sizes(sizes.size / 2))

  /** Pixel size for a key, falling back to the default for unknown keys. */
  def pixelsFor(key: String): Int = allowed.getOrElse(key, default.pixels)

  /** Resolves a `(dimension token, key)` pair from a public URL to a dimension and pixel size. */
  def resolve(dimensionToken: String, key: String): (ThumbnailDimension, Int) =
    (ThumbnailDimension.fromToken(dimensionToken).getOrElse(ThumbnailDimension.Width), pixelsFor(key))

object ThumbnailResolutions:

  /** Temporary default used by server-built clip URLs until clips support dimensions. */
  val DefaultKey = "s"
