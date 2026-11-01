package nl.amony.platform.subprocess

/**
 * How ffmpeg decodes video frames for derived previews. Generating a storyboard sprite is almost
 * entirely video decoding, so handing that to the GPU's fixed-function media block (VAAPI on Linux,
 * which also backs Intel Quick Sync) is the largest speed-up available on a CPU-limited machine.
 */
enum HardwareDecode(val configName: String):

  /** Use VAAPI when a GPU render node is readable, otherwise decode in software. */
  case Auto extends HardwareDecode("auto")

  /** Use VAAPI, falling back to software for jobs the device or codec cannot handle. */
  case Vaapi extends HardwareDecode("vaapi")

  /** Always decode in software. */
  case Disabled extends HardwareDecode("none")
