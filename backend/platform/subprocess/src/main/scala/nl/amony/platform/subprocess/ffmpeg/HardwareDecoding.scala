package nl.amony.platform.subprocess.ffmpeg

import java.nio.file.{Files, Path}

import nl.amony.platform.subprocess.HardwareDecode

/**
 * VAAPI decoding for a single ffmpeg invocation.
 *
 * Frames stay on the GPU until they have been scaled down to the preview size, so the expensive full
 * resolution work happens on the fixed-function media block and only tiny frames are copied back to
 * system memory for the software filters that tile them.
 */
final case class HardwareDecoding(device: String):

  /** Global and input options that move the decoder to VAAPI; these must precede the `-i` argument. */
  def inputArgs: List[String] =
    List(
      "-init_hw_device",
      s"vaapi=va:$device",
      "-hwaccel",
      "vaapi",
      "-hwaccel_device",
      "va",
      "-hwaccel_output_format",
      "vaapi"
    )

  /** Scales on the GPU, then downloads frames of the target size so `fps` and `tile` can run in software. */
  def scaleFilter(width: Int, height: Int): String =
    s"scale_vaapi=w=$width:h=$height,hwdownload,format=nv12"

object HardwareDecoding:

  val defaultDevice = "/dev/dri/renderD128"

  /**
   * Resolves the configured mode against the machine. `auto` quietly decodes in software where no
   * readable render node exists, so a host without a GPU does not fail every preview.
   */
  def resolve(mode: HardwareDecode, device: String): Option[HardwareDecoding] =
    mode match
      case HardwareDecode.Disabled => None
      case HardwareDecode.Vaapi    => Some(HardwareDecoding(device))
      case HardwareDecode.Auto     => Option.when(Files.isReadable(Path.of(device)))(HardwareDecoding(device))
