package nl.amony.lib.process.magick

import cats.effect.IO
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.magick.tasks.{CreateThumbnail, GetImageMetaData}

class ImageMagick(meter: Meter[IO]) extends ProcessRunner(meter) with GetImageMetaData with CreateThumbnail {}
