package nl.amony.lib.ffmpeg

import nl.amony.lib.FileUtil.{PathOps, stripExtension}
import nl.amony.lib.ffmpeg.tasks.{CreateThumbnail, CreateThumbnailTile, FFProbe, ProcessRunner}
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration

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

  def addFastStart(video: Path): Path = {

    val out = s"${video.stripExtension}-faststart.mp4"

    logger.info(s"Adding faststart at: $out")

    // format: off
    runSync(
      useErrorStream = true,
      cmds = List(
        "ffmpeg",
        "-i",        video.absoluteFileName(),
        "-c",        "copy",
        "-map",      "0",
        "-movflags", "+faststart",
        "-y",        out
      )
    )
    // format: on

    Path.of(out)
  }

  def copyMp4(
      inputFile: Path,
      start: Long,
      end: Long,
      outputFile: Option[Path] = None
  ): Unit = {
    val input  = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    // format: off
    val args = List(
        "-ss",       formatTime(start),
        "-to",       formatTime(end),
        "-i",        input,
        "-c",        "copy",
        "-map",      "0",
        "-movflags", "+faststart",
        "-y",        output
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def transcodeToMp4(
      inputFile: Path,
      range: (Long, Long),
      outputFile: Option[Path] = None,
      quality: Int = 24,
      scaleHeight: Option[Int]
  ): Unit = {

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
        "-crf", s"$quality",
        "-an", // no audio
        "-v",   "quiet",
        "-y",   output
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def streamFragment(
      inputFile: String,
      from: Long,
      to: Long,
      quality: Int = 23,
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
        "-c:v", "libx264",
        "-movflags", "+faststart+frag_keyframe+empty_moov",
        "-crf", s"$quality",
        "-f", "mp4",
        "-an", // no audio
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
