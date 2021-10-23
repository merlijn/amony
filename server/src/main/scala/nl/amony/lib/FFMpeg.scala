package nl.amony.lib

import better.files.File
import nl.amony.lib.FileUtil.{PathOps, stripExtension}
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration

object FFMpeg extends Logging {

  case class Probe(
    duration: Long,
    resolution: (Int, Int),
    fps: Double,
    fastStart: Boolean
  )

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
     inputFile: Path,
     timestamp: Long,
     scaleHeight: Int
  ): InputStream = {

    val args = List(
      "-ss",      formatTime(timestamp),
      "-i" ,      inputFile.toString,
      "-vcodec",  "webp",
      "-vf",      s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f",       "image2pipe",
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
        "-quality", "80", // 1 - 31 (best-worst) for jpeg, 1-100 (worst-best) for webp
        "-vframes", "1",
        "-y",       outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }



  def generatePreviewSprite(
     inputFile: Path,
     outputDir: Path,
     height: Int = 100,
     outputBaseName: Option[String] = None,
     frameInterval: Option[Int] = None
  ) = {

    def calculateNrOfFrames(length: Long): (Int, Int) = {

      // 2, 3,  4,  5,  6,  7,  8,  9,  10,  11,  12
      // 4, 9, 16, 25, 36, 49, 64, 81, 100, 121, 144
      val minFrames = 4
      val maxFrames = 64

      val frames = Math.min(maxFrames, Math.max(minFrames, length / (10 * 1000)))
      val tileSize = Math.ceil(Math.sqrt(frames.toDouble)).toInt

      frames.toInt -> tileSize
    }

    val maximumFrames = 256

    val fileBaseName = outputBaseName.getOrElse(inputFile.getFileName.stripExtension())

    val probe = ffprobe(inputFile)
    val (frames, tileSize) = calculateNrOfFrames(probe.duration)
    val mod = ((probe.fps * (probe.duration / 1000)) / frames).toInt

    val width: Int = ((probe.resolution._1.toDouble / probe.resolution._2) * height).toInt

//    logger.info(s"fps: ${probe.fps}, length: ${probe.duration}, frames: $frames, tileSize: $tileSize, mod: $mod")

    val args = List(
      "-i" ,      inputFile.toAbsolutePath.toString,
      "-filter_complex", s"select='not(mod(n,$mod))',scale=$width:$height,tile=${tileSize}x${tileSize}",
      "-vframes",  "1",
      "-qscale:v", "3",
      "-y",
      "-an", s"$outputDir/$fileBaseName.jpeg"
    )

    def genVtt(): String = {
      val thumbLength: Int = (probe.duration / frames).toInt

      val builder = new StringBuilder()

      builder.append("WEBVTT\n")

      (0 until frames).foreach { n =>
        val start = formatTime(thumbLength * n)
        val end = formatTime(thumbLength * (n + 1))
        val x: Int = n % tileSize
        val y: Int = Math.floor(n / tileSize).toInt

        builder.append(
          s"""
             |${n + 1}
             |${start} --> $end
             |$fileBaseName.jpeg#xywh=${x * width},${y * height},${width},${height}
             |""".stripMargin
        )
      }

      builder.toString()
    }

    runSync(true, "ffmpeg" :: args)

    (File(outputDir) / s"$fileBaseName.vtt").write(genVtt())
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
