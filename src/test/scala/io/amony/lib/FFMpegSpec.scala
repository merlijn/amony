package io.amony.lib

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class FFMpegSpec extends AnyFlatSpecLike {

  val testStreams = List(
    "Stream #0:0(und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 1920x1080, 5594 kb/s, 29.97 fps, 29.97 tbr, 30k tbn, 60k tbc (default)",
    "Stream #0:0(und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"
  )

  val stream0 = "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"

  val ffProbeOutput =
   s"""
      |some lines ...
      |...
      |Stream #0:0(und): ${testStreams(0)}
      |...
      |""".stripMargin

  it should "extract stream info" in {

    val FFMpeg.streamPattern(line) = ffProbeOutput

    line shouldBe testStreams(0)
  }

  it should("extract fps") in {

    val output = testStreams.zipWithIndex.map {
      case (v, i) => FFMpeg.extractFps(v, s"test case ${i}")
    }

    output shouldBe
      List(
        Some(29.97D),
        Some(24D)
      )
  }
}
