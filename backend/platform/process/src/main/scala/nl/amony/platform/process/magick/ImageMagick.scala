package nl.amony.platform.process.magick

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.platform.process.ProcessRunner
import nl.amony.platform.process.magick.tasks.GetImageMetaData

class ImageMagick(using meter: Meter[IO], tracer: Tracer[IO]) extends ProcessRunner with GetImageMetaData {}
