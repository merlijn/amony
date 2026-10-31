package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

import cats.effect.IO
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.FFMpeg.formatTime
import nl.amony.platform.subprocess.{Command, ProcessRunner}

/** Metadata of a generated storyboard sprite: its layout, the sampled interval and the source duration. */
case class ThumbnailTile(
  width: Int,
  height: Int,
  tileSize: Int,
  frameCount: Int,
  intervalInMillis: Long,
  durationInMillis: Long
)

object ThumbnailTile:

  def frameCount(durationInMillis: Long, intervalInMillis: Long): Int = {
    require(intervalInMillis > 0, "interval must be positive")
    math.max(1, math.ceil(durationInMillis.toDouble / intervalInMillis).toInt)
  }

  def tileSize(frameCount: Int): Int =
    math.max(1, math.ceil(math.sqrt(frameCount.toDouble)).toInt)

  /** Height for a width-pinned frame, rounded up to an even number so h264/webp encoders accept it. */
  def heightFor(width: Int, sourceWidth: Int, sourceHeight: Int): Int =
    if sourceWidth <= 0 || sourceHeight <= 0 then width
    else
      val scaled = math.round(width.toDouble * sourceHeight / sourceWidth).toInt
      math.max(2, if scaled % 2 == 0 then scaled else scaled + 1)

  /** Renders a WebVTT manifest that points at the sprite URL and addresses each frame via `#xywh`. */
  def renderWebVtt(tile: ThumbnailTile, spriteUrl: String): String = {
    val builder = new StringBuilder("WEBVTT\n")

    (0 until tile.frameCount).foreach { n =>
      val start = n.toLong * tile.intervalInMillis
      val end   = math.max(start + 1, math.min(tile.durationInMillis, start + tile.intervalInMillis))
      val x     = (n % tile.tileSize) * tile.width
      val y     = (n / tile.tileSize) * tile.height

      builder.append("\n")
      builder.append(s"${formatTime(start)} --> ${formatTime(end)}\n")
      builder.append(s"$spriteUrl#xywh=$x,$y,${tile.width},${tile.height}\n")
    }

    builder.toString
  }

trait CreateThumbnailTile extends Logging {

  self: ProcessRunner & FFProbe =>

  /**
   * Generates a single storyboard sprite holding one frame every `intervalInMillis`, scaled to `width`.
   * The sprite is created lazily when it does not exist yet and the returned metadata is used to write
   * the accompanying WebVTT manifest.
   */
  def createThumbnailTile(
    input: URI,
    outputFile: Path,
    width: Int             = 96,
    intervalInMillis: Long = 10000L,
    overwrite: Boolean     = false
  ): IO[ThumbnailTile] = {

    ffprobe(input, false, 5.seconds).flatMap { (ffProbeOutput, _) =>
      val stream       = ffProbeOutput.firstVideoStream.getOrElse(throw new IllegalStateException("no video stream found"))
      val duration     = stream.durationMillis.toLong
      val frames       = ThumbnailTile.frameCount(duration, intervalInMillis)
      val tileSize     = ThumbnailTile.tileSize(frames)
      val height       = ThumbnailTile.heightFor(width, stream.width, stream.height)
      val tile         = ThumbnailTile(width, height, tileSize, frames, intervalInMillis, duration)
      val intervalSecs = intervalInMillis.toDouble / 1000.0
      val output       = outputFile.toAbsolutePath.normalize.toString

      if Files.exists(outputFile) && !overwrite then IO.pure(tile)
      else {
        // Select the first frame and one frame every interval, then tile them into a single sprite.
        val filter = s"select='isnan(prev_selected_t)+gte(t-prev_selected_t\\,$intervalSecs)',scale=$width:$height,tile=${tileSize}x$tileSize"

        val args = List(
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

        runIgnoreOutput("ffmpeg-create-thumbnail-tile", Command("ffmpeg", args)).as(tile)
      }
    }
  }
}
