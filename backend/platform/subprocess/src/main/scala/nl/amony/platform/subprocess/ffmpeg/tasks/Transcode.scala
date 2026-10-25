package nl.amony.platform.subprocess.ffmpeg.tasks

import java.nio.file.Path

import cats.effect.IO
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.FFMpeg.{formatTime, scaleFilter, stripExtension}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

case class TranscodeProfile(ext: String, args: List[String])

object TranscodeProfile {
  val default = TranscodeProfile(ext = "mp4", args = "-c:v libx264 -crf 23 -movflags +faststart".split(' ').toList)
}

trait Transcode extends Logging {

  self: ProcessRunner =>

  def transcodeToMp4(
    inputFile: Path,
    range: (Long, Long),
    includeAudio: Boolean     = true,
    profile: TranscodeProfile = TranscodeProfile.default,
    scaleWidth: Option[Int]   = None,
    scaleHeight: Option[Int]  = None,
    outputFile: Option[Path]  = None
  ): IO[Path] = {

    val (ss, to) = range
    val input    = inputFile.toAbsolutePath.normalize.toString
    val output   =
      outputFile.map(_.toAbsolutePath.normalize.toString).getOrElse(s"${stripExtension(inputFile).toAbsolutePath.normalize}.${profile.ext}")

    val args: List[String] = List("-ss", formatTime(ss), "-to", formatTime(to), "-i", input) ++
      scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++
      profile.args ++ Option.when(!includeAudio)("-an") ++
      List("-v", "quiet", "-y", output)

    runIgnoreOutput("ffmpeg-transcode", Command("ffmpeg", args)).map(_ => Path.of(output))
  }

  def streamStranscodeMp4(inputFile: Path, scaleHeight: Option[Int] = None): fs2.Stream[IO, Byte] = {

    // format: off
    val args: List[String] =
      List("-i", inputFile.toAbsolutePath.normalize.toString) ++
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

    fs2.Stream.force(useProcess("ffmpeg-stream-transcode", Command("ffmpeg", args))(p => IO(p.stdout)))
  }
}
