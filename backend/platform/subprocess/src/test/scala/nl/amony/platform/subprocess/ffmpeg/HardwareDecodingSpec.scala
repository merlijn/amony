package nl.amony.platform.subprocess.ffmpeg

import java.nio.file.Files

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.platform.subprocess.HardwareDecode

class HardwareDecodingSpec extends AnyWordSpecLike {

  "HardwareDecoding.resolve" should {

    "never use hardware when it is disabled" in
      assert(HardwareDecoding.resolve(HardwareDecode.Disabled, "/dev/dri/renderD128").isEmpty)

    "always use hardware when it is forced" in
      assert(HardwareDecoding.resolve(HardwareDecode.Vaapi, "/dev/dri/renderD128").contains(HardwareDecoding("/dev/dri/renderD128")))

    "use hardware for auto when the device is readable" in {
      val device = Files.createTempFile("render-node", "")
      try assert(HardwareDecoding.resolve(HardwareDecode.Auto, device.toString).isDefined)
      finally Files.deleteIfExists(device)
    }

    "fall back to software for auto when the device does not exist" in
      assert(HardwareDecoding.resolve(HardwareDecode.Auto, "/nonexistent/dri/renderD128").isEmpty)
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
