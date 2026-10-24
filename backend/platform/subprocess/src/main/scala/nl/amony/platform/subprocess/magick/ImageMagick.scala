package nl.amony.platform.subprocess.magick

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.platform.subprocess.ProcessRunner
import nl.amony.platform.subprocess.magick.tasks.GetImageMetaData

class ImageMagick(using meter: Meter[IO], tracer: Tracer[IO]) extends ProcessRunner with GetImageMetaData {}
