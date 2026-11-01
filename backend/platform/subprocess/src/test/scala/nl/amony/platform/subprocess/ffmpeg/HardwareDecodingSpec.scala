package nl.amony.platform.subprocess.ffmpeg

import java.nio.file.Files

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.platform.subprocess.HardwareDecodeConfig

class HardwareDecodingSpec extends AnyWordSpecLike {

  "HardwareDecoding.resolve" should {

    "use the configured device when it is readable" in {
      val device = Files.createTempFile("render-node", "")
      try {
        val resolved = HardwareDecoding.resolve(HardwareDecodeConfig(enabled = true, vaapiDevice = device.toString))

        assert(resolved == Right(HardwareDecoding(device.toString)))
      } finally Files.deleteIfExists(device)
    }

    "report a reason instead of a device when it is not readable" in
      assert(
        HardwareDecoding.resolve(HardwareDecodeConfig(enabled = true, vaapiDevice = "/nonexistent/dri/renderD128")) ==
          Left("no readable VAAPI device at /nonexistent/dri/renderD128")
      )
  }

  "HardwareDecoding" should {

    "initialise the VAAPI device and let ffmpeg download the decoded frames" in {
      val args = HardwareDecoding("/dev/dri/renderD128").inputArgs

      assert(args.take(2) == List("-init_hw_device", "vaapi=va:/dev/dri/renderD128"))
      assert(args.containsSlice(List("-hwaccel", "vaapi")))
      // No fixed output format: frames come back in their native format, so 10-bit sources stay 10-bit.
      assert(!args.contains("-hwaccel_output_format"))
    }
  }
}
