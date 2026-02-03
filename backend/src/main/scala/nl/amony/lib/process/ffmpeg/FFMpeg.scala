package nl.amony.lib.process.ffmpeg

import java.nio.file.Path
import java.time.Duration

import cats.effect.IO
import org.typelevel.otel4s.metrics.MeterProvider
import scribe.Logging

import nl.amony.lib.files.*
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.ffmpeg.FFMpeg.formatTime
import nl.amony.lib.process.ffmpeg.tasks.{AddFastStart, CreateThumbnail, CreateThumbnailTile, FFProbe}

case class TranscodeProfile(ext: String, args: List[String])

object TranscodeProfile {
  val default = TranscodeProfile(ext = "mp4", args = "-c:v libx264 -crf 23 -movflags +faststart".split(' ').toList)
}

object FFMpeg {
  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern = raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }
}

class FFMpeg(meterProvider: MeterProvider[IO]) extends Logging with ProcessRunner(meterProvider) with CreateThumbnail with CreateThumbnailTile
    with FFProbe with AddFastStart {

  def transcodeToMp4(
    inputFile: Path,
    range: (Long, Long),
    includeAudio: Boolean     = true,
    profile: TranscodeProfile = TranscodeProfile.default,
    scaleHeight: Option[Int]  = None,
    outputFile: Option[Path]  = None
  ): IO[Path] = {

    val (ss, to) = range
    val input    = inputFile.absoluteFileName()
    val output   = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.${profile.ext}")

    val args: List[String] = List("-ss", formatTime(ss), "-to", formatTime(to), "-i", input) ++
      scaleHeight.toList.flatMap(height => List("-vf", s"scale=-2:$height")) ++ profile.args ++ Option.when(!includeAudio)("-an") ++
      List("-v", "quiet", "-y", output)

    runIgnoreOutput("ffmpeg", args).map(_ => Path.of(output))
  }

  def streamStranscodeMp4(inputFile: Path, scaleHeight: Option[Int] = None): fs2.Stream[IO, Byte] = {

    // format: off
    val args: List[String] =
      List("-i",   inputFile.absoluteFileName()) ++
      scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-c:v",      "libx264",
        "-movflags", "+faststart+frag_keyframe+empty_moov",
        "-crf",      "23",
        "-f",        "mp4",
        "-an",       // no audio
        "pipe:1"
      )
    // format: on

    fs2.Stream.force(useProcess("ffmpeg", args)(p => IO(p.stdout)))
  }

  def streamThumbnail(inputFile: Path, timestamp: Long, scaleHeight: Int): fs2.Stream[IO, Byte] = {

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i" ,      inputFile.toString,
      "-vcodec",  "webp",
      "-vf",      s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f",       "image2pipe",
      "-"
    )
    // format: on

    fs2.Stream.force(useProcess("ffmpeg", args)(p => IO(p.stdout)))
  }
}
