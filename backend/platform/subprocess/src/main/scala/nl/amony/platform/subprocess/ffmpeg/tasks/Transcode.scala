package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import java.nio.file.Path

import cats.effect.IO
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.FFMpeg.{formatTime, scaleFilter}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

case class TranscodeProfile(ext: String, args: List[String])

object TranscodeProfile {
  val default = TranscodeProfile(ext = "mp4", args = "-c:v libx264 -crf 23 -movflags +faststart".split(' ').toList)
}

trait Transcode extends Logging {

  self: ProcessRunner =>

  def transcodeToMp4(
    input: URI,
    range: (Long, Long),
    includeAudio: Boolean     = true,
    profile: TranscodeProfile = TranscodeProfile.default,
    scaleWidth: Option[Int]   = None,
    scaleHeight: Option[Int]  = None,
    outputFile: Path
  ): IO[Path] = {

    val (ss, to) = range
    val output   = outputFile.toAbsolutePath.normalize.toString

    val args: List[String] = List("-ss", formatTime(ss), "-to", formatTime(to), "-i", FFMpeg.inputArg(input)) ++
      scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++
      profile.args ++ Option.when(!includeAudio)("-an") ++
      List("-v", "quiet", "-y", output)

    runIgnoreOutput("ffmpeg-transcode", Command("ffmpeg", args), settings.clipTimeout).map(_ => Path.of(output))
  }

  def streamStranscodeMp4(input: URI, scaleHeight: Option[Int] = None): fs2.Stream[IO, Byte] = {

    // format: off
    val args: List[String] =
      List("-i", FFMpeg.inputArg(input)) ++
        scaleHeight.toList.flatMap(height => List("-vf", s"scale=-2:$height")) ++
        List(
          "-c:v", "libx264",
          "-movflags", "+faststart+frag_keyframe+empty_moov",
          "-crf", "23",
          "-f", "mp4",
          "-an", // no audio
          "pipe:1"
        )
    // format: on

    streamProcess("ffmpeg-stream-transcode", Command("ffmpeg", args), settings.clipTimeout)(_.stdout)
  }
}
