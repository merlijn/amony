package nl.amony.modules.resources.api

enum ImageFormat(val configName: String, val extension: String, val mimeType: String):
  case Avif extends ImageFormat("avif", "avif", "image/avif")
  case Jxl  extends ImageFormat("jxl", "jxl", "image/jxl")
  case Webp extends ImageFormat("webp", "webp", "image/webp")
  case Jpeg extends ImageFormat("jpeg", "jpeg", "image/jpeg")

  def minimumDimension: Option[Int] = this match
    case Avif => Some(64) // SVT-AV1 rejects any side below 64px
    case _    => None

object ImageFormat:
  def fromName(name: String): Option[ImageFormat] = values.find(_.configName == name)

final class ThumbnailFormats(supported: List[ImageFormat], encoderOptions: Map[ImageFormat, List[String]] = Map.empty):

  require(supported.nonEmpty, "At least one thumbnail image format must be configured")
  require(supported.distinct.size == supported.size, s"Duplicate thumbnail image formats: ${supported.map(_.configName).mkString(", ")}")

  /** Configured formats, in client preference order. */
  val formats: List[ImageFormat] = supported

  val default: ImageFormat = formats.head

  /** Resolves a format name from a public URL, falling back to the most preferred format. */
  def resolve(name: String): ImageFormat =
    ImageFormat.fromName(name).filter(formats.contains).getOrElse(default)

  /** Extra ffmpeg encoder arguments configured for the given format (empty when none are configured). */
  def encoderArgs(format: ImageFormat): List[String] = encoderOptions.getOrElse(format, Nil)
