package nl.amony.lib

import better.files.File
import monix.eval.Task
import nl.amony.lib.FFMpeg.model.ProbeDebugOutput
import nl.amony.lib.FileUtil.{PathOps, stripExtension}
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.util.Try

object FFMpeg extends Logging {

  object model {
    import io.circe._, io.circe.generic.semiauto._

    case class ProbeDebugOutput(
      isFastStart: Boolean
    )

    case class ProbeOutput(
      streams: List[Stream]
    ) {
      def firstVideoStream: Option[VideoStream] = streams.collectFirst {
        case v: VideoStream => v
      }
    }

    val durationPattern = raw"(\d{2}):(\d{2}):(\d{2})".r.unanchored

    sealed trait Stream

    case object UnkownStream extends Stream

    case class AudioStream(
      codec_name: String
    ) extends Stream

    case class VideoStream(
      codec_name: String,
      width: Int,
      height: Int,
      duration: String,
      bit_rate: String,
      avg_frame_rate: String,
      tags: Map[String, String]
    ) extends Stream {
      def durationMillis: Long = (duration.toDouble * 1000L).toLong
      def fps: Double = {
        val splitted = avg_frame_rate.split('/')
        val divident = splitted(0).toDouble
        val divisor = splitted(1).toDouble

        divident / divisor
      }
    }

    implicit val videoStreamDecoder: Decoder[VideoStream] = deriveDecoder[VideoStream]
    implicit val audioStreamDecoder: Decoder[AudioStream] = deriveDecoder[AudioStream]
    implicit val probeDecoder: Decoder[ProbeOutput] = deriveDecoder[ProbeOutput]

    implicit val streamDecoder: Decoder[Stream] = new Decoder[Stream] {
      final def apply(c: HCursor): Decoder.Result[Stream] = {

        c.downField("codec_type").as[String].flatMap {
          case "video" => c.as[VideoStream]
          case "audio" => c.as[AudioStream]
          case _       => Right(UnkownStream)
        }
      }
    }
  }

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern =
    raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  def ffprobeOld(file: Path): (model.ProbeOutput, model.ProbeDebugOutput) = {

    val fileName = file.toAbsolutePath.normalize().toString
    val debugOutput = runSync(useErrorStream = true, cmds = List("ffprobe", "-v", "debug", fileName))
    (null, ProbeDebugOutput(fastStartPattern.matches(debugOutput)))
  }

  def ffprobe(file: Path, debug: Boolean = false): (model.ProbeOutput, model.ProbeDebugOutput) = {

    import model._

    val fileName = file.toAbsolutePath.normalize().toString

    // setting -v to debug will hang the standard output stream on some files.
    val process   = run(cmds = List("ffprobe", "-print_format", "json", "-show_streams", "-v", "quiet", fileName))

//    Task {
//      val result = process.waitFor(3000, TimeUnit.MILLISECONDS)
//      if (!result) {
//        logger.info(s"Timed out after 3 seconds")
//        process.destroy()
//      }
//    }.runToFuture(monix.execution.Scheduler.Implicits.global)

    val jsonOutput = scala.io.Source.fromInputStream(process.getInputStream).mkString
//    val debugOutput = readStream(process.getErrorStream)
//    val debugProbe = ProbeDebugOutput(fastStartPattern.matches(debugOutput))
    val debugProbe = ProbeDebugOutput(true)

    io.circe.parser.decode[ProbeOutput](jsonOutput) match {
      case Left(error) => throw error
      case Right(out)  => (out, debugProbe)
    }
  }

  private def readStream(is: InputStream) = {
    val builder = new StringBuilder

    try {
      scala.io.Source.fromInputStream(is).iter.foreach { c =>
        print(c)
        builder.append(c)
      }
    } catch {
      case e: Exception => logger.warn(e)
    }
    builder.toString()
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

  def copyMp4(
     inputFile: Path,
     start: Long,
     end: Long,
     outputFile: Option[Path] = None,
  ): Unit = {
    val input = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    val args = List(
      "-ss",  formatTime(start),
      "-to",  formatTime(end),
      "-i",   input,
      "-c",   "copy",
      "-movflags", "+faststart",
      "-y", output
      )

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def transcodeToMp4(
      inputFile: Path,
      from: Long,
      to: Long,
      outputFile: Option[Path] = None,
      quality: Int = 24,
      scaleHeight: Option[Int]
  ): Unit = {

    val input = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    // format: off
    val args: List[String] =
      List(
        "-ss",  formatTime(from),
        "-to",  formatTime(to),
        "-i",   input,
      ) ++
        scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-movflags", "+faststart",
        "-crf", s"$quality",
        "-an", // no audio
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

    run("ffmpeg" :: args).getInputStream
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

    run("ffmpeg" :: args).getInputStream
  }

  def writeThumbnail(
    inputFile: Path,
    timestamp: Long,
    outputFile: Option[Path],
    scaleHeight: Option[Int]
  ): Unit = {

    val input = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       input
    ) ++ scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-quality", "80", // 1 - 31 (best-worst) for jpeg, 1-100 (worst-best) for webp
        "-vframes", "1",
        "-y",       output
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

    val (probe, _) = ffprobe(inputFile)
    val stream = probe.firstVideoStream.getOrElse(throw new IllegalStateException("no video stream found"))
    val (frames, tileSize) = calculateNrOfFrames(stream.durationMillis)
    val mod = ((stream.fps * (stream.durationMillis / 1000)) / frames).toInt

    val width: Int = ((stream.width / stream.height) * height).toInt

//    logger.info(s"fps: ${probe.fps}, length: ${probe.duration}, frames: $frames, tileSize: $tileSize, mod: $mod")

    val args = List(
      "-i" ,      inputFile.absoluteFileName(),
      "-filter_complex", s"select='not(mod(n,$mod))',scale=$width:$height,tile=${tileSize}x${tileSize}",
      "-vframes",  "1",
      "-qscale:v", "3",
      "-y",
      "-an", s"$outputDir/$fileBaseName.jpeg"
    )

    def genVtt(): String = {
      val thumbLength: Int = (stream.durationMillis / frames).toInt

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

    val process  = Runtime.getRuntime.exec(cmds.toArray)
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }

  def run(cmds: Seq[String]): Process = {
    Runtime.getRuntime.exec(cmds.toArray)
  }
}
