package nl.amony.lib.ffmpeg

import org.scalatest.flatspec.AnyFlatSpecLike

class FFMpegSpec extends AnyFlatSpecLike with Logging {

  val testStreams = List(
    "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 1920x1080, 5594 kb/s, 29.97 fps, 29.97 tbr, 30k tbn, 60k tbc (default)",
    "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"
  )

  val stream0 =
    "Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 3840x2160, 24187 kb/s, 24 fps, 24 tbr, 24 tbn, 48 tbc (default)"

  val yesSeeks = """[AVIOContext @ 0x131e20110] Statistics: 79926 bytes read, 2 seeks"""
  val noSeeks  = """[AVIOContext @ 0x131e20110] Statistics: 79926 bytes read, 0 seeks"""

  val ffProbeOutput =
    s"""
       |some lines ...
       |...
       |Stream #0:0(und): ${testStreams(0)}
       |...
       |""".stripMargin

  it should "create a sprite" in {

    val times = Seq(
      10 * 1000,
      60 * 1000,
      10 * 60 * 1000,
      30 * 60 * 1000,
      60 * 60 * 1000,
      120 * 60 * 1000,
    )

    times.foreach { t =>

      val frames = FFMpeg.calculateNrOfFrames(t)

      println(s"${t / 1000} -> $frames")
    }
  }
}