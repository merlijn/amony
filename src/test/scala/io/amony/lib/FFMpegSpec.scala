package io.amony.lib

import io.amony.actor.MediaLibCommandHandler
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class FFMpegSpec extends AnyFlatSpecLike {

  val testStreams = List(
    "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 1920x1080, 5594 kb/s, 29.97 fps, 29.97 tbr, 30k tbn, 60k tbc (default)",
    "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"
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

  it should("insert in to list") in {

    List(0, 1, 2).replaceAtPos(0, 42) shouldBe List(42, 1, 2)
    List(0, 1, 2).replaceAtPos(1, 42) shouldBe List(0, 42, 2)
    List(0, 1, 2).replaceAtPos(2, 42) shouldBe List(0, 1, 42)

    // index greater or equal to with results in append
    List(0, 1, 2).replaceAtPos(3, 42) shouldBe List(0, 1, 2, 42)
    List(0, 1, 2).replaceAtPos(4, 42) shouldBe List(0, 1, 2, 42)

    List(0).replaceAtPos(0, 42) shouldBe List(42)
    List.empty.replaceAtPos(0, 42) shouldBe List(42)



    List(0, 1, 2).deleteAtPos(0) shouldBe List(1, 2)
    List(0, 1, 2).deleteAtPos(1) shouldBe List(0, 2)
    List(0, 1, 2).deleteAtPos(2) shouldBe List(0, 1)
    List(0, 1, 2).deleteAtPos(3) shouldBe List(0, 1, 2)
  }
}
