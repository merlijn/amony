package nl.amony.modules.resources.api

/** Which side of a thumbnail is pinned to a resolution; the other follows the source aspect ratio. */
enum ThumbnailDimension(val token: String, val name: String):
  case Width  extends ThumbnailDimension("w", "width")
  case Height extends ThumbnailDimension("h", "height")

object ThumbnailDimension:
  def fromToken(token: String): Option[ThumbnailDimension] = values.find(_.token == token)

/**
 * The fixed, configurable set of thumbnail resolutions (pixel sizes), ordered smallest to largest.
 *
 * Clients request one of the configured sizes by number; `stepDown` lets the server serve an even
 * smaller size (e.g. to trade quality for bandwidth). Unknown sizes fall back to the default. Keeping
 * the set closed prevents a client from requesting arbitrary sizes and polluting the thumbnail cache.
 */
final class ThumbnailResolutions(allowed: List[Int], defaultSize: Int, stepDown: Int):

  require(allowed.nonEmpty, "At least one thumbnail resolution must be configured")
  require(allowed.contains(defaultSize), s"Default thumbnail resolution $defaultSize is not in the configured resolutions: ${allowed.mkString(", ")}")
  require(stepDown >= 0, s"resolution-step-down must be >= 0, got $stepDown")

  /** Configured sizes, smallest to largest. */
  val sizes: List[Int] = allowed.distinct.sorted

  val dimensions: List[ThumbnailDimension] = ThumbnailDimension.values.toList

  val default: Int = defaultSize

  /**
   * The size actually served for a requested size: unknown sizes fall back to the default, then the
   * configured number of ladder steps is subtracted (clamped to the smallest configured size).
   */
  def effectiveSize(requested: Int): Int = {
    val index = sizes.indexOf(requested) match
      case -1 => sizes.indexOf(default)
      case i  => i
    sizes(math.max(0, index - stepDown))
  }

  /** Resolves a `(dimension token, requested size)` pair from a public URL to a dimension and size. */
  def resolve(dimensionToken: String, requested: Int): (ThumbnailDimension, Int) =
    (ThumbnailDimension.fromToken(dimensionToken).getOrElse(ThumbnailDimension.Width), effectiveSize(requested))

object ThumbnailResolutions:

  /** Fixed size used for server-built clip previews until clips support dimensions/resolutions. */
  val DefaultClipSize = 512
