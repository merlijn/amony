package io.amony.lib

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class FFMpegSpec extends AnyFlatSpecLike {

  val examples = List(
    "Stream #0:0(und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 1920x1080, 5594 kb/s, 29.97 fps, 29.97 tbr, 30k tbn, 60k tbc (default)",
    "Stream #0:0(und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"
  )

  it should("work") in {

    val output = examples.zipWithIndex.map {
      case (v, i) => FFMpeg.extractFps(v, s"test case ${i}")
    }

    output shouldBe
      List(
        Some(29.97D),
        Some(24D)
      )
  }
}
