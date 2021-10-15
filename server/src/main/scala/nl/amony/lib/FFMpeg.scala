package nl.amony.lib

import nl.amony.lib.FileUtil.{PathOps, stripExtension}
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration

object FFMpeg extends Logging {

  case class Probe(duration: Long, resolution: (Int, Int), fps: Double, fastStart: Boolean)

  val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
  val res     = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

  val fpsPattern = raw"Stream #0.*,\s([\w\.]+)\sfps".r.unanchored

  val streamPattern = """\s*Stream #.*(Video.*)""".r.unanchored

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern =
    raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  def extractFps(ffprobeOutput: String, hint: String): Option[Double] = {
    ffprobeOutput match {
      case fpsPattern(fps) => Some(fps.toDouble)
      case _ =>
        logger.warn(s"Failed to extract fps info from '$hint''")
        None
    }
  }

  def ffprobeParse(output: String, hint: String): Probe = {
    val (w, h) = output match {
      case res(w, h) =>
        (w.toInt, h.toInt)
      case _ =>
        logger.warn(s"Failed to extract fps info from '$hint'")
        (0, 0)
    }

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt * 60 * 60 * 1000 +
          minutes.toInt * 60 * 1000 +
          seconds.toInt * 1000
    }

    val fastStart = fastStartPattern.matches(output)

    val fps = extractFps(output, hint).getOrElse(0d)

    Probe(duration, (w, h), fps, fastStart)
  }

  def ffprobe(file: Path): Probe = {

    val fileName = file.toAbsolutePath.toString
    val output   = runSync(useErrorStream = true, cmds = List("ffprobe", "-v", "debug", fileName))

    ffprobeParse(output, file.toString)
  }

  private def formatTime(timestamp: Long): String = {

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

  def createWebP(
      inputFile: String,
      timestamp: Long,
      durationInSeconds: Int = 3,
      outputFile: Option[String] = None,
      scaleHeight: Option[Int]
  ): Unit = {

    // format: off

    val args = List(
      "-ss",       formatTime(timestamp),
      "-t",        durationInSeconds.toString,
      "-i",        inputFile,
    ) ++ scaleHeight.toList.flatMap(sh => List("-vf", s"fps=8,scale=-2:$sh:flags=lanczos")) ++
      List("-vcodec",   "libwebp",
        "-lossless", "0",
        "-loop",     "0",
        "-q:v",      "80",
        "-preset",   "picture",
        "-vsync",    "0",
        "-an",
        "-y", outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def writeMp4(
      inputFile: String,
      from: Long,
      to: Long,
      outputFile: Option[String] = None,
      quality: Int = 24,
      scaleHeight: Option[Int]
  ): Unit = {

    // format: off
    val args: List[String] =
      List(
        "-ss",  formatTime(from),
        "-to",  formatTime(to),
        "-i",   inputFile,
      ) ++
        scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-movflags", "+faststart",
        "-crf", s"$quality",
        "-an", // no audio
        "-y",   outputFile.getOrElse(s"${stripExtension(inputFile)}.mp4")
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

    runAsync(false, "ffmpeg" :: args)
  }

  def streamThumbnail(
     inputFile: String,
     timestamp: Long,
     scaleHeight: Int
  ): InputStream = {

    // var args = ['-ss', '00:00:20', '-i', fsPath, '-vf', 'select=eq(pict_type\\,PICT_TYPE_I),scale=640:-1,tile=2x2', '-f', 'image2pipe', '-vframes', '1', '-'];
    val args = List(
      "-ss", formatTime(timestamp),
      "-i" , inputFile,
      "-vcodec", "webp",
      "-vf", s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f", "image2pipe",
      "-"
    )

    runAsync(false, "ffmpeg" :: args)
  }

  def writeThumbnail(
    inputFile: String,
    timestamp: Long,
    outputFile: Option[String],
    scaleHeight: Option[Int]
  ): Unit = {

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       inputFile
    ) ++ scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-quality", "80", // 1 - 30 (best-worst) for jpeg, 1-100 (worst-best) for webp
        "-vframes", "1",
        "-y",       outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def runSync(useErrorStream: Boolean, cmds: Seq[String]): String = {

    logger.debug(s"Running command: ${cmds.mkString(",")}")

    val runtime  = Runtime.getRuntime
    val process  = runtime.exec(cmds.toArray)
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }

  def runAsync(useErrorStream: Boolean, cmds: Seq[String]): InputStream = {
    val runtime  = Runtime.getRuntime
    val process  = runtime.exec(cmds.toArray)
    if (useErrorStream) process.getErrorStream else process.getInputStream
  }
}