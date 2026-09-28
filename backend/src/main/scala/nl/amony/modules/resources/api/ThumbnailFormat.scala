package nl.amony.modules.resources.api

/** An image format a thumbnail can be encoded in. */
enum ImageFormat(val configName: String, val extension: String, val mimeType: String):
  case Avif extends ImageFormat("avif", "avif", "image/avif")
  case Jxl  extends ImageFormat("jxl", "jxl", "image/jxl")
  case Webp extends ImageFormat("webp", "webp", "image/webp")
  case Jpeg extends ImageFormat("jpeg", "jpeg", "image/jpeg")

object ImageFormat:
  def fromName(name: String): Option[ImageFormat] = values.find(_.configName == name)

/**
 * The closed, ordered set of image formats the server can encode into.
 *
 * The order is the client's preference order (best compression first): the frontend picks the first
 * format its browser can decode. The first entry is also the server-side fallback for unknown or
 * unsupported format names in a URL, so clients cannot request arbitrary formats.
 */
final class ThumbnailFormats(supported: List[ImageFormat]):

  require(supported.nonEmpty, "At least one thumbnail image format must be configured")
  require(supported.distinct.size == supported.size, s"Duplicate thumbnail image formats: ${supported.map(_.configName).mkString(", ")}")

  /** Configured formats, in client preference order. */
  val formats: List[ImageFormat] = supported

  val default: ImageFormat = formats.head

  /** Resolves a format name from a public URL, falling back to the most preferred format. */
  def resolve(name: String): ImageFormat =
    ImageFormat.fromName(name).filter(formats.contains).getOrElse(default)
