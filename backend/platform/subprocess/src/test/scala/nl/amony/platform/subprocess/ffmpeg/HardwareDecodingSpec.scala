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

    "initialise the VAAPI device and keep decoded frames on the GPU" in {
      val args = HardwareDecoding("/dev/dri/renderD128").inputArgs

      assert(args.take(2) == List("-init_hw_device", "vaapi=va:/dev/dri/renderD128"))
      assert(args.containsSlice(List("-hwaccel", "vaapi")))
      assert(args.containsSlice(List("-hwaccel_output_format", "vaapi")))
    }

    "download frames only after the GPU scaled them down" in
      assert(HardwareDecoding("/dev/dri/renderD128").scaleFilter(192, 108) == "scale_vaapi=w=192:h=108,hwdownload,format=nv12")
  }
}
