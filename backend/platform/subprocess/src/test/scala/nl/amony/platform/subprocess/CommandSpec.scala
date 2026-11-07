package nl.amony.platform.subprocess

import org.scalatest.wordspec.AnyWordSpecLike

class CommandSpec extends AnyWordSpecLike {

  "Command.of" should {

    "mix flags, options and positional args" in {
      val command = Command.of(
        "ffmpeg",
        "-ss"       -> "00:00:01.000",
        "-i"        -> "input.mp4",
        "-frames:v" -> "1",
        "-y",
        "-an",
        "output.webp"
      )

      assert(command.cmd == "ffmpeg")
      assert(command.args == List("-ss", "00:00:01.000", "-i", "input.mp4", "-frames:v", "1", "-y", "-an", "output.webp"))
    }

    "splice raw argument lists" in {
      val command = Command.of("ffmpeg", "-v" -> "error", List("-c", "copy", "-map", "0"), "-y", "out.mp4")

      assert(command.args == List("-v", "error", "-c", "copy", "-map", "0", "-y", "out.mp4"))
    }

    "drop absent options" in {
      val scale: Option[(String, String)] = None
      val flag: Option[String]            = None
      val command                         = Command.of("ffmpeg", "-i" -> "in.mp4", scale, flag, "-y", "out.mp4")

      assert(command.args == List("-i", "in.mp4", "-y", "out.mp4"))
    }

    "keep present options" in {
      val scale: Option[(String, String)] = Some("-vf" -> "scale=192:-2")
      val flag: Option[String]            = Some("-an")
      val command                         = Command.of("ffmpeg", "-i" -> "in.mp4", scale, flag, "out.mp4")

      assert(command.args == List("-i", "in.mp4", "-vf", "scale=192:-2", "-an", "out.mp4"))
    }
  }
}
