package nl.amony.platform.subprocess.ffmpeg

import java.net.URI
import java.nio.file.{Path, Paths}
import java.time.Duration

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.tasks.*
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessRunner, ProcessSettings}

object FFMpeg {

  /** Builds a scale filter pinning the given width or height (the other dimension follows the source aspect ratio). */
  def scaleFilter(width: Option[Int], height: Option[Int]): Option[String] =
    width.map(w => s"scale=$w:-2").orElse(height.map(h => s"scale=-2:$h"))

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def stripExtension(path: Path): Path =
    val fileName = path.getFileName.toString
    val dotIdx   = fileName.lastIndexOf('.')
    if dotIdx >= 0 then path.resolveSibling(fileName.substring(0, dotIdx)) else path

  def stripExtension(fileName: String): String =
    val dotIdx = fileName.lastIndexOf('.')
    if dotIdx >= 0 then fileName.substring(0, dotIdx) else fileName

  /**
   * Renders an input URI as an ffmpeg/ffprobe `-i` argument. `file` URIs become plain filesystem paths, so
   * behavior for local files is unchanged; anything else (https, e.g. S3 presigned URLs) is passed through
   * untouched, letting ffmpeg stream it directly.
   */
  def inputArg(input: URI): String =
    if input.getScheme == "file" then Paths.get(input).toAbsolutePath.normalize.toString
    else input.toString

  /** Last segment of the URI path, e.g. the file name of a local path or the object key of a presigned URL. */
  def fileNameOf(input: URI): String =
    Option(input.getPath).flatMap(_.split("/").lastOption).filter(_.nonEmpty).getOrElse("input")
}

class FFMpeg(override protected val settings: ProcessSettings, override protected val limiter: ProcessLimiter)(
  using meter: Meter[IO],
  tracer: Tracer[IO]
) extends Logging with ProcessRunner
    with CreateThumbnail with CreateThumbnailTile
    with FFProbe with AddFastStart with Transcode
