package nl.amony.platform.subprocess.ffmpeg

import java.nio.file.Path
import java.time.Duration

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.platform.subprocess.ProcessRunner
import nl.amony.platform.subprocess.ffmpeg.tasks.*

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
}

class FFMpeg(using meter: Meter[IO], tracer: Tracer[IO]) extends Logging with ProcessRunner
    with CreateThumbnail with CreateThumbnailTile
    with FFProbe with AddFastStart with Transcode
