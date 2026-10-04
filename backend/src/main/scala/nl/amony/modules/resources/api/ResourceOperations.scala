package nl.amony.modules.resources.api

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

object VideoFragment {
  val minWidth          = 64
  val minHeight         = 64
  val maxWidth          = 8192
  val maxHeight         = 8192
  val minLengthInMillis = 1000
  val maxLengthInMillis = 60000

  /** Fixed length of a generated hover-preview clip, in milliseconds. */
  val PreviewLengthMillis = 3000L

  /**
   * The `(start, end)` range of a resource's preview clip: it starts at the resource's thumbnail
   * timestamp (or a third of the video) and runs for [[PreviewLengthMillis]], capped at the video length.
   */
  def previewRange(thumbnailTimestamp: Option[Int], durationInMillis: Int): (Long, Long) =
    val start = thumbnailTimestamp.getOrElse(durationInMillis / 3).toLong
    (start, math.min(durationInMillis.toLong, start + PreviewLengthMillis))
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
    minimumDimension: Option[Int] = None
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

    (Option.when(dimension == ThumbnailDimension.Width)(sized), Option.when(dimension == ThumbnailDimension.Height)(sized))
  }

  /**
   * Every derived operation to materialize for a resource: each supported thumbnail format at every
   * configured resolution, plus the preview clip for videos. The frontend pins the configured
   * operative dimension (width), so only width-pinned operations are generated here.
   *
   * The caller is expected to discard operations that do not [[ResourceOperation.validate]] against
   * the resource (e.g. a video too short for a preview clip).
   */
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
            val (width, height) = scaledDimensions(size, ThumbnailDimension.Width, source, even = true, minimumDimension = format.minimumDimension)
            VideoThumbnail(width, height, timestamp, format)

        val clips: List[ResourceOperation] = sizes.map { size =>
          val (width, height) = scaledDimensions(size, ThumbnailDimension.Width, source, even = true)
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
