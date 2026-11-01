package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import java.nio.file.{Files, Path}

import cats.effect.IO
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.FFMpeg.formatTime
import nl.amony.platform.subprocess.{Command, ProcessRunner}

/** The part of a storyboard that a single sprite covers. */
case class ThumbnailTile(
  width: Int,
  height: Int,
  columns: Int,
  frameCount: Int,
  intervalInMillis: Long,
  startInMillis: Long,
  quality: Int
) {
  require(width > 0, "width must be positive")
  require(height > 0, "height must be positive")
  require(columns > 0, "columns must be positive")
  require(frameCount > 0, "frame count must be positive")
  require(frameCount <= columns * columns, s"frame count must fit in the $columns x $columns sprite grid")
  require(intervalInMillis > 0, "interval must be positive")
  require(startInMillis >= 0, "start must not be negative")
  require(quality >= 0 && quality <= 100, "quality must be between 0 and 100")

  /** Rows actually needed for this sprite, so a partially filled last sprite does not carry empty rows. */
  val rows: Int = (frameCount + columns - 1) / columns
}

object ThumbnailTile {

  /**
   * Resamples the window at exactly one frame per interval and tiles the result. `fps` keeps the
   * output timestamps aligned with the manifest's cue times, unlike selecting the first frame after
   * each interval, which drifts and can leave cells empty at the end of a sprite when the interval
   * is not a multiple of the source frame duration. `nb_frames` stops the tile at the planned frame
   * count instead of waiting for the end of the window.
   */
  def filter(tile: ThumbnailTile): String =
    s"fps=1000/${tile.intervalInMillis},scale=${tile.width}:${tile.height},tile=${tile.columns}x${tile.rows}:nb_frames=${tile.frameCount}"

  /**
   * Interval above which a sprite samples frames far enough apart that the decoder can skip
   * everything but keyframes. Cell contents then follow the nearest preceding keyframe instead of the
   * exact cue time: invisible at a five or ten second step, but it would visibly shift the contents
   * at the finer steps, which need the frames in between.
   */
  val keyframeOnlyAboveMillis = 2000L

  def skipToKeyframes(intervalInMillis: Long): Boolean = intervalInMillis > keyframeOnlyAboveMillis

  /** Decoder arguments for a sprite. These are per-input options, so they must precede the `-i` argument. */
  def decodeArgs(tile: ThumbnailTile): List[String] =
    Option.when(skipToKeyframes(tile.intervalInMillis))(List("-skip_frame", "nokey")).getOrElse(Nil)
}

trait CreateThumbnailTile extends Logging {

  self: ProcessRunner =>

  /**
   * Generates one storyboard sprite for the given window, holding a frame every `intervalInMillis`
   * and tiled into `columns` columns with as many rows as the frame count needs. The window is
   * seeked to directly, so a sprite for a later part of a long video does not require decoding
   * everything before it.
   *
   * Coarse intervals additionally decode only keyframes, which is where most of the work goes for
   * long videos.
   */
  def createThumbnailTile(
    input: URI,
    outputFile: Path,
    tile: ThumbnailTile,
    overwrite: Boolean = false
  ): IO[Unit] =

    if Files.exists(outputFile) && !overwrite then IO.unit
    else {
      val window = tile.frameCount.toLong * tile.intervalInMillis
      val output = outputFile.toAbsolutePath.normalize.toString

      val args = ThumbnailTile.decodeArgs(tile) ++ List(
        "-ss",
        formatTime(tile.startInMillis),
        "-t",
        formatTime(window),
        "-i",
        FFMpeg.inputArg(input),
        "-vf",
        ThumbnailTile.filter(tile),
        "-frames:v",
        "1",
        "-quality",
        tile.quality.toString,
        "-v",
        "error",
        "-y",
        "-an",
        output
      )

      runIgnoreOutput("ffmpeg-create-thumbnail-tile", Command("ffmpeg", args), settings.tileTimeout).void
    }
}
