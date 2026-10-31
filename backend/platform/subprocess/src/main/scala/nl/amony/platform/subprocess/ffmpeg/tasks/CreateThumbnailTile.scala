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
  gridSize: Int,
  frameCount: Int,
  intervalInMillis: Long,
  startInMillis: Long
)

trait CreateThumbnailTile extends Logging {

  self: ProcessRunner =>

  /**
   * Generates one storyboard sprite for the given window, holding a frame every `intervalInMillis`
   * and tiled into a `gridSize x gridSize` square. The window is seeked to directly, so a sprite for
   * a later part of a long video does not require decoding everything before it.
   */
  def createThumbnailTile(
    input: URI,
    outputFile: Path,
    tile: ThumbnailTile,
    overwrite: Boolean = false
  ): IO[Unit] = {

    require(tile.width > 0, "width must be positive")
    require(tile.height > 0, "height must be positive")
    require(tile.gridSize > 0, "grid size must be positive")
    require(tile.frameCount > 0, "frame count must be positive")
    require(tile.intervalInMillis > 0, "interval must be positive")
    require(tile.startInMillis >= 0, "start must not be negative")

    if Files.exists(outputFile) && !overwrite then IO.unit
    else {
      val intervalSecs = tile.intervalInMillis.toDouble / 1000.0
      val window       = tile.frameCount.toLong * tile.intervalInMillis
      val output       = outputFile.toAbsolutePath.normalize.toString

      // Select the first frame and one frame every interval, then tile them into a single sprite.
      val filter =
        s"select='isnan(prev_selected_t)+gte(t-prev_selected_t\\,$intervalSecs)',scale=${tile.width}:${tile.height},tile=${tile.gridSize}x${tile.gridSize}"

      val args = List(
        "-ss",
        formatTime(tile.startInMillis),
        "-t",
        formatTime(window),
        "-i",
        FFMpeg.inputArg(input),
        "-vf",
        filter,
        "-frames:v",
        "1",
        "-quality",
        "80",
        "-y",
        "-an",
        output
      )

      runIgnoreOutput("ffmpeg-create-thumbnail-tile", Command("ffmpeg", args)).void
    }
  }
}
