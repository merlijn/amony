package nl.amony.lib.process.ffmpeg

import java.time.Duration

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.ffmpeg.tasks.*

object FFMpeg {

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }
}

class FFMpeg(using meter: Meter[IO], tracer: Tracer[IO]) extends Logging with ProcessRunner
    with CreateThumbnail with CreateThumbnailTile
    with FFProbe with AddFastStart with Transcode
