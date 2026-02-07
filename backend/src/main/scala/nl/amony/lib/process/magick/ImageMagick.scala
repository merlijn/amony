package nl.amony.lib.process.magick

import cats.effect.IO
import org.typelevel.otel4s.metrics.MeterProvider

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.magick.tasks.{CreateThumbnail, GetImageMetaData}

class ImageMagick(meterProvider: MeterProvider[IO]) extends ProcessRunner(meterProvider) with GetImageMetaData with CreateThumbnail {}
