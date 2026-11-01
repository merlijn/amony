package nl.amony.platform.subprocess.ffmpeg

import java.nio.file.{Files, Path}

import nl.amony.platform.subprocess.{HardwareDecodeConfig, HardwareDecodeMode}

/**
 * VAAPI decoding for a single ffmpeg invocation.
 *
 * Only the decoding happens on the GPU. Frames are downloaded at full resolution and scaled in
 * software, because the GPU-side scaler (`scale_vaapi`) needs the VPP pipeline, which drivers do not
 * always expose — Intel's iHD on Gen9 for one. With keyframe-only decoding only a few dozen small
 * frames are involved, so the download is cheap; without it the copy dominates and the GPU decode is
 * still faster than decoding on the CPU.
 */
final case class HardwareDecoding(device: String):

  /**
   * Global and input options that move the decoder to VAAPI; these must precede the `-i` argument.
   * No `-hwaccel_output_format` is set on purpose: frames then come back in their native format, so a
   * 10-bit source is not forced through an 8-bit download.
   */
  def inputArgs: List[String] =
    List(
      "-init_hw_device",
      s"vaapi=va:$device",
      "-hwaccel",
      "vaapi",
      "-hwaccel_device",
      "va"
    )

object HardwareDecoding:

  val defaultDevice = "/dev/dri/renderD128"

  /**
   * Resolves the configured backend, or a description of why the device cannot be used. The reason is
   * reported as-is, so a configured but unusable GPU ends up as a warning instead of a failure.
   */
  def resolve(config: HardwareDecodeConfig): Either[String, HardwareDecoding] =
    config.mode match
      case HardwareDecodeMode.Vaapi =>
        Either.cond(
          Files.isReadable(Path.of(config.vaapiDevice)),
          HardwareDecoding(config.vaapiDevice),
          s"no readable VAAPI device at ${config.vaapiDevice}"
        )
