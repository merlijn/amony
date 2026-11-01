package nl.amony.platform.subprocess

import scala.concurrent.duration.*

/** Limits and timeouts for external media processes (ffmpeg, ffprobe and ImageMagick). */
final case class ProcessSettings(
  maxConcurrent: Int                   = 6,
  maxQueued: Int                       = 32,
  queueTimeout: FiniteDuration         = 10.seconds,
  ffprobeTimeout: FiniteDuration       = 5.seconds,
  imageMetadataTimeout: FiniteDuration = 10.seconds,
  thumbnailTimeout: FiniteDuration     = 30.seconds,
  clipTimeout: FiniteDuration          = 2.minutes,
  tileTimeout: FiniteDuration          = 3.minutes,
  remuxTimeout: FiniteDuration         = 10.minutes,
  hardwareDecode: HardwareDecodeConfig = HardwareDecodeConfig()
):

  require(maxConcurrent > 0, s"maxConcurrent must be > 0, got $maxConcurrent")
  require(maxQueued > 0, s"maxQueued must be > 0, got $maxQueued")
  require(queueTimeout > Duration.Zero, s"queueTimeout must be positive, got $queueTimeout")
  require(ffprobeTimeout > Duration.Zero, s"ffprobeTimeout must be positive, got $ffprobeTimeout")
  require(imageMetadataTimeout > Duration.Zero, s"imageMetadataTimeout must be positive, got $imageMetadataTimeout")
  require(thumbnailTimeout > Duration.Zero, s"thumbnailTimeout must be positive, got $thumbnailTimeout")
  require(clipTimeout > Duration.Zero, s"clipTimeout must be positive, got $clipTimeout")
  require(tileTimeout > Duration.Zero, s"tileTimeout must be positive, got $tileTimeout")
  require(remuxTimeout > Duration.Zero, s"remuxTimeout must be positive, got $remuxTimeout")
