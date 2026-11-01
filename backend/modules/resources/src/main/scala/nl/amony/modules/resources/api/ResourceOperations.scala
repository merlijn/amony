package nl.amony.modules.resources.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

import nl.amony.modules.resources.util.Base32

sealed trait ResourceOperation {
  def contentType: String
  def validate(info: ResourceInfo): Either[String, Unit]
}

case class VideoThumbnail(width: Option[Int] = None, height: Option[Int] = None, timestamp: Long, format: ImageFormat) extends ResourceOperation {
  override def contentType: String = format.mimeType

  override def validate(info: ResourceInfo): Either[String, Unit] = info.basicContentProperties match {
    case Some(video: VideoProperties) =>
      for _ <- Either.cond(timestamp > 0 && timestamp < video.durationInMillis, (), "Timestamp is out of bounds") yield ()
    case other                        => Left("Wrong content type, expected video, got: " + other)
  }
}

object VideoThumbnailTile:
  val defaultPixels    = 20480
  val columnsPerSprite = 5
  val rowsPerSprite    = 5
  val framesPerSprite  = columnsPerSprite * rowsPerSprite
  val spriteQuality    = 70

  /** Bump when the manifest layout or sprite generation changes, so cached manifests are not reused. */
  val manifestVersion = 3

  private[api] def validate(info: ResourceInfo, width: Int, intervalInMillis: Long, index: Option[Int] = None): Either[String, Unit] =
    info.basicContentProperties match
      case Some(video: VideoProperties) =>
        for
          _ <- Either.cond(width > 0, (), "Width must be positive")
          _ <- Either.cond(intervalInMillis > 0, (), "Interval must be positive")
          _ <- index match
                 case Some(value) =>
                   val count = spriteCount(video.durationInMillis.toLong, intervalInMillis)
                   Either.cond(value >= 1 && value <= count, (), s"Sprite index $value is out of bounds (1..$count)")
                 case None        => Right(())
        yield ()
      case other                        => Left("Wrong content type, expected video, got: " + other)

  def totalFrames(durationInMillis: Long, intervalInMillis: Long): Int =
    math.max(1, math.ceil(durationInMillis.toDouble / intervalInMillis).toInt)

  def spriteCount(durationInMillis: Long, intervalInMillis: Long): Int =
    val frames = totalFrames(durationInMillis, intervalInMillis)
    math.max(1, (frames + framesPerSprite - 1) / framesPerSprite)

  /** 0-based frame where the given 1-based sprite starts. */
  def startFrame(index: Int): Long =
    (index - 1).toLong * framesPerSprite

  def startInMillis(index: Int, intervalInMillis: Long): Long =
    startFrame(index) * intervalInMillis

  def frameCountInSprite(index: Int, durationInMillis: Long, intervalInMillis: Long): Int =
    val remaining = totalFrames(durationInMillis, intervalInMillis) - startFrame(index)
    math.max(0, math.min(framesPerSprite.toLong, remaining).toInt)

  /** Rounds up to an even number of pixels, since some encoders reject odd dimensions. */
  private def even(value: Int): Int = math.max(2, if value % 2 == 0 then value else value + 1)

  /**
   * Width for a frame of roughly `targetPixels` area at the source aspect ratio. Using the area
   * instead of a fixed width keeps landscape and portrait sprites the same size. The height follows
   * from [[heightFor]]; both sides are kept even for the encoders.
   */
  def widthFor(targetPixels: Int, sourceWidth: Int, sourceHeight: Int): Int =
    if sourceWidth <= 0 || sourceHeight <= 0 then even(math.sqrt(targetPixels.toDouble).round.toInt)
    else
      val aspect = sourceWidth.toDouble / sourceHeight
      even(math.sqrt(targetPixels.toDouble * aspect).round.toInt)

  /** Height for a width-pinned frame, rounded up to an even number so the encoders accept it. */
  def heightFor(width: Int, sourceWidth: Int, sourceHeight: Int): Int =
    if sourceWidth <= 0 || sourceHeight <= 0 then even(width)
    else even(math.round(width.toDouble * sourceHeight / sourceWidth).toInt)

  def manifestFileName(resourceId: ResourceId, contentVersion: String, width: Int, intervalInMillis: Long, token: String): String =
    s"${resourceId}_${contentVersion}_tile_${intervalInMillis}ms_w${width}_$token.vtt"

  def spriteFileName(
    resourceId: ResourceId,
    contentVersion: String,
    width: Int,
    intervalInMillis: Long,
    index: Int,
    token: String,
    extension: String = "webp"
  ): String =
    s"${resourceId}_${contentVersion}_tile_${intervalInMillis}ms_w${width}_${token}_$index.$extension"

  /** Renders the complete WebVTT manifest, referencing one sprite file per group of [[framesPerSprite]] frames. */
  def renderWebVtt(
    durationInMillis: Long,
    intervalInMillis: Long,
    width: Int,
    height: Int,
    spriteUrl: Int => String
  ): String = {
    val frames  = totalFrames(durationInMillis, intervalInMillis)
    val builder = new StringBuilder("WEBVTT\n")

    (0 until frames).foreach { n =>
      val spriteIndex   = n / framesPerSprite + 1
      val frameInSprite = n              % framesPerSprite
      val x             = (frameInSprite % columnsPerSprite) * width
      val y             = (frameInSprite / columnsPerSprite) * height
      val start         = n.toLong * intervalInMillis
      val end           = math.max(start + 1, math.min(durationInMillis, start + intervalInMillis))

      builder.append("\n")
      builder.append(s"${formatTime(start)} --> ${formatTime(end)}\n")
      builder.append(s"${spriteUrl(spriteIndex)}#xywh=$x,$y,$width,$height\n")
    }

    builder.toString
  }

  private def formatTime(timestamp: Long): String = {
    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

/** One storyboard sprite containing a group of up to [[VideoThumbnailTile.framesPerSprite]] frames. */
case class VideoThumbnailTile(
  width: Int,
  intervalInMillis: Long,
  index: Int = 1,
  token: String
) extends ResourceOperation {
  override def contentType: String = "image/webp"

  override def validate(info: ResourceInfo): Either[String, Unit] =
    VideoThumbnailTile.validate(info, width, intervalInMillis, Some(index))
}

/** The WebVTT manifest describing all frames and their sprite files. */
case class VideoThumbnailTileVtt(width: Int, intervalInMillis: Long, token: String) extends ResourceOperation {
  override def contentType: String = "text/vtt"

  override def validate(info: ResourceInfo): Either[String, Unit] =
    VideoThumbnailTile.validate(info, width, intervalInMillis)
}

object VideoFragment {
  val minWidth          = 64
  val minHeight         = 64
  val maxWidth          = 8192
  val maxHeight         = 8192
  val minLengthInMillis = 1000
  val maxLengthInMillis = 60000

  /** Fixed length of a generated hover-preview clip, in milliseconds. */
  val previewLengthMillis = 3000L

  /**
   * The `(start, end)` range of a resource's preview clip: it starts at the resource's thumbnail
   * timestamp (or a third of the video) and runs for [[previewLengthMillis]], capped at the video length.
   */
  def previewRange(thumbnailTimestamp: Option[Int], durationInMillis: Int): (Long, Long) =
    val start = thumbnailTimestamp.getOrElse(durationInMillis / 3).toLong
    (start, math.min(durationInMillis.toLong, start + previewLengthMillis))
}

case class VideoFragment(width: Option[Int] = None, height: Option[Int] = None, start: Long, end: Long) extends ResourceOperation {

  import VideoFragment.*

  override def contentType: String = "video/mp4"

  override def validate(info: ResourceInfo): Either[String, Unit] = {
    info.basicContentProperties match {
      case Some(_: VideoProperties) =>
        val duration = end - start
        for
          _ <- Either.cond(width.forall(_ >= minWidth) && height.forall(_ >= minHeight), (), "Size too small")
          _ <- Either.cond(width.forall(_ <= maxWidth) && height.forall(_ <= maxHeight), (), "Size too large")
          _ <- Either.cond(start >= 0, (), "Start time is negative")
          _ <- Either.cond(end > start, (), "End time is before start time")
          _ <- Either.cond(duration > minLengthInMillis, (), "Duration too short")
          _ <- Either.cond(duration < maxLengthInMillis, (), "Duration too long")
        yield ()
      case other                    => Left("Wrong content type, expected video, got: " + other)
    }
  }
}

object ImageThumbnail {
  val minHeight = 64
  val minWidth  = 64
  val maxHeight = 8192
  val maxWidth  = 8192
}

case class ImageThumbnail(width: Option[Int] = None, height: Option[Int] = None, format: ImageFormat) extends ResourceOperation {

  import ImageThumbnail.*

  override def contentType: String = format.mimeType

  override def validate(info: ResourceInfo): Either[String, Unit] =
    for
      _ <- Either.cond(width.forall(_ >= minWidth) && height.forall(_ >= minHeight), (), "Size too small")
      _ <- Either.cond(width.forall(_ <= maxWidth) && height.forall(_ <= maxHeight), (), "Size too large")
    yield ()
}

object ResourceOperations:

  /**
   * Bump this when the preview generation logic or the ffmpeg/ImageMagick toolchain changes, so that cached previews
   * and browser validators are invalidated.
   */
  val previewRecipeVersion = 1

  /**
   * Length of the content version token in Base32 characters (8 chars = 40 bits). Deliberately short:
   * versions are only ever compared pairwise (URL token vs. current), where 40 bits gives a ~1e-12
   * collision chance per comparison, unlike content hashes which match library-wide.
   */
  val contentVersionLength = 8

  /**
   * Version token for a resource's content, derived from the fields that change when the bytes change.
   * Used in thumbnail/clip URLs and derived file names so any content change yields fresh URLs instead of
   * stale cached responses. Pure renames keep the same version.
   */
  def contentVersion(info: ResourceInfo): String = {
    val digest                    = MessageDigest.getInstance("SHA-1")
    def feed(value: String): Unit = {
      digest.update(value.getBytes(StandardCharsets.UTF_8))
      digest.update(0.toByte)
    }

    feed(info.partialHash.getOrElse(""))
    feed(info.timeLastModified.map(_.toString).getOrElse(""))
    feed(info.fileKey.getOrElse(""))
    feed(info.size.toString)

    Base32.encode(digest.digest()).substring(0, contentVersionLength)
  }

  /** The resource's native pixel dimensions (0, 0 when unknown). */
  def sourceDimensions(resource: ResourceInfo): (Int, Int) = resource.basicContentProperties match
    case Some(video: VideoProperties) => (video.width, video.height)
    case Some(image: ImageProperties) => (image.width, image.height)
    case None                         => (0, 0)

  /**
   * The dimension ffmpeg derives for the unpinned side via `scale=w:-2` / `scale=-2:h`, using floor
   * division: requiring `>= min` on this value is safe even though ffmpeg rounds to the nearest even
   * number, since the real result is never below the floor.
   */
  private def derivedFloor(pinned: Int, pinnedSource: Int, otherSource: Int): Int =
    if pinnedSource <= 0 then 0 else ((pinned.toLong * otherSource) / pinnedSource).toInt

  /**
   * Resolves the requested size for a pinned dimension, capped at the source's native size so a
   * thumbnail is never upscaled. For video the size is also rounded down to an even number, since
   * the h264 encoder requires even dimensions.
   *
   * When the target format has a hard minimum on both sides (`minimumDimension`, e.g. AVIF), the
   * pinned size grows so the derived side stays at or above that minimum; the even rounding flips
   * upwards in that case if rounding down would drop the derived side below the minimum.
   */
  def scaledDimensions(
    requested: Int,
    dimension: ThumbnailDimension,
    source: (Int, Int),
    even: Boolean,
    minimumDimension: Option[Int] = None,
    squarePixels: Boolean         = false
  ): (Option[Int], Option[Int]) = {
    val (sourceWidth, sourceHeight) = source
    val pinnedSource                = if dimension == ThumbnailDimension.Width then sourceWidth else sourceHeight
    val otherSource                 = if dimension == ThumbnailDimension.Width then sourceHeight else sourceWidth
    val minimum                     = minimumDimension.filter(_ > 0)

    // Smallest pinned size whose derived side reaches the encoder minimum (0 when unbounded or unknown).
    val requiredForMinimum = minimum match
      case Some(min) if pinnedSource > 0 && otherSource > 0 => ((min.toLong * pinnedSource + otherSource - 1) / otherSource).toInt
      case _                                                => 0

    val target = math.max(requested, requiredForMinimum)
    val capped = if pinnedSource > 0 then math.min(target, pinnedSource) else target
    val sized  =
      if !even then capped
      else
        val roundedDown  = capped - (capped % 2)
        val belowMinimum = minimum.exists(min => derivedFloor(roundedDown, pinnedSource, otherSource) < min)
        if belowMinimum then roundedDown + 2 else roundedDown

    // Anamorphic sources must pin both sides: otherwise ffmpeg derives the other side from the
    // coded dimensions and the output keeps a non-square pixel aspect ratio.
    val derived =
      if !squarePixels || pinnedSource <= 0 then None
      else
        val scaled = math.max(1, math.round(sized.toDouble * otherSource / pinnedSource).toInt)
        Some(if even then math.max(2, if scaled % 2 == 0 then scaled else scaled + 1) else scaled)

    if dimension == ThumbnailDimension.Width then (Some(sized), derived) else (derived, Some(sized))
  }

  /** Every thumbnail and preview-clip operation configured for a resource. */
  def all(info: ResourceInfo, resolutions: ThumbnailResolutions, formats: ThumbnailFormats): List[ResourceOperation] = {
    val sizes = resolutions.sizes
    info.basicContentProperties match
      case Some(video: VideoProperties) =>
        val source                              = sourceDimensions(info)
        val timestamp                           = info.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
        val (start, end)                        = VideoFragment.previewRange(info.thumbnailTimestamp, video.durationInMillis)
        val thumbnails: List[ResourceOperation] =
          for
            format <- formats.formats
            size   <- sizes
          yield
            val (width, height) = scaledDimensions(
              size,
              ThumbnailDimension.Width,
              source,
              even             = true,
              minimumDimension = format.minimumDimension,
              squarePixels     = video.pixelAspectRatio.isDefined
            )
            VideoThumbnail(width, height, timestamp, format)

        val clips: List[ResourceOperation] = sizes.map { size =>
          val (width, height) = scaledDimensions(
            size,
            ThumbnailDimension.Width,
            source,
            even         = true,
            squarePixels = video.pixelAspectRatio.isDefined
          )
          VideoFragment(width, height, start, end)
        }

        thumbnails ++ clips

      case Some(_: ImageProperties) =>
        val source = sourceDimensions(info)
        for
          format <- formats.formats
          size   <- sizes
        yield
          val (width, height) = scaledDimensions(size, ThumbnailDimension.Width, source, even = false, minimumDimension = format.minimumDimension)
          ImageThumbnail(width, height, format)

      case _ => Nil
  }
