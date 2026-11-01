package nl.amony.platform.subprocess.magick

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.platform.subprocess.magick.tasks.GetImageMetaData
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessRunner, ProcessSettings}

class ImageMagick(override protected val settings: ProcessSettings, override protected val limiter: ProcessLimiter)(
  using meter: Meter[IO],
  tracer: Tracer[IO]
) extends ProcessRunner with GetImageMetaData {}
