package nl.amony.lib.ffmpeg

import monix.eval.Task
import nl.amony.lib.ffmpeg.FFMpeg.formatTime
import nl.amony.lib.ffmpeg.tasks._
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.files.PathOps
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration

object dsl {

  type Param = (String, Option[String])

  def params(params: (String, Option[String])*): Seq[String] =
    params.toList.flatMap { case (name, value) => name :: value.toList }

  def startTime(ts: Long): Param = "-ss" -> Some(formatTime(ts))
  def endTime(ts: Long): Param =  "-to" -> Some(formatTime(ts))
  def input(fileName: String): Param = "-i" -> Some(fileName)
  def crf(q: Int): Param = "-crf" -> Some(q.toString)
  def noAudio(): Param = "-an" -> None
}

object FFMpeg extends Logging
  with ProcessRunner
  with CreateThumbnail
  with CreateThumbnailTile
  with FFProbe {

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern =
    raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def addFastStart(video: Path): Task[Path] = {

    val out = s"${video.stripExtension}-faststart.mp4"

    logger.info(s"Adding faststart at: $out")

    runWithOutput(
      cmds = List(
        "ffmpeg",
        "-i",        video.absoluteFileName(),
        "-c",        "copy",
        "-map",      "0",
        "-movflags", "+faststart",
        "-y",        out
      ),
      useErrorStream = true
    )(_ => Task(Path.of(out)))
  }

  def transcodeToMp4(
      inputFile: Path,
      range: (Long, Long),
      crf: Int = 24,
      scaleHeight: Option[Int],
      outputFile: Option[Path] = None
  ): Task[Path] = {

    val (ss, to) = range
    val input    = inputFile.absoluteFileName()
    val output   = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    // format: off
    val args: List[String] =
      List(
        "-ss",  formatTime(ss),
        "-to",  formatTime(to),
        "-i",   input,
      ) ++
        scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-movflags", "+faststart",
        "-crf", s"$crf",
        "-an", // no audio
        "-v",   "quiet",
        "-y",   output
      )
    // format: on

    runWithOutput[Path](cmds = "ffmpeg" :: args, useErrorStream = true) { _ => Task.now(Path.of(output)) }
  }

  def streamFragment(
      inputFile: String,
      from: Long,
      to: Long,
      crf: Int = 23,
      scaleHeight: Option[Int] = None
  ): InputStream = {

    // format: off
    val args: List[String] =
    List(
      "-ss",  formatTime(from),
      "-to",  formatTime(to),
      "-i",   inputFile,
    ) ++
      scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-c:v",      "libx264",
        "-movflags", "+faststart+frag_keyframe+empty_moov",
        "-crf",      s"$crf",
        "-f",        "mp4",
        "-an",       // no audio
        "pipe:1"
      )
    // format: on

    runUnsafe("ffmpeg" :: args).getInputStream
  }

  def streamThumbnail(
      inputFile: Path,
      timestamp: Long,
      scaleHeight: Int
  ): InputStream = {

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

    runUnsafe("ffmpeg" :: args).getInputStream
  }

}
