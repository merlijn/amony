package nl.amony.platform.subprocess

import nl.amony.platform.subprocess.ffmpeg.HardwareDecoding

/**
 * How ffmpeg decodes video frames for derived previews.
 *
 * Generating a preview is almost entirely video decoding, so a GPU's fixed-function media block can be
 * much faster than the CPU. Only VAAPI (Intel Quick Sync and AMD on Linux) is supported for now.
 */
enum HardwareDecodeMode(val configName: String):

  /** VAAPI, which drives the media block of Intel and AMD GPUs on Linux. */
  case Vaapi extends HardwareDecodeMode("vaapi")

object HardwareDecodeMode:
  def fromConfigName(name: String): Option[HardwareDecodeMode] = values.find(_.configName == name)

/**
 * Hardware decoding settings for the media processes. Off by default: it needs a GPU device passed into
 * the container and a working driver, so enabling it is an explicit opt-in.
 */
final case class HardwareDecodeConfig(
  enabled: Boolean         = false,
  mode: HardwareDecodeMode = HardwareDecodeMode.Vaapi,
  vaapiDevice: String      = HardwareDecoding.defaultDevice
)
