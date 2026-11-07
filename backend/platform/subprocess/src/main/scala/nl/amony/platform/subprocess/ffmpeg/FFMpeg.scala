package nl.amony.platform.subprocess.ffmpeg

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.circe.Json
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.tasks.FFProbeModel.{*, given}
import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile
import nl.amony.platform.subprocess.{Command, ProcessRunner}

case class TranscodeProfile(ext: String, args: List[String])

object TranscodeProfile {
  val default = TranscodeProfile(ext = "mp4", args = "-c:v libx264 -crf 23 -movflags +faststart".split(' ').toList)
}

object FFMpeg extends Logging {

  /** Builds a scale filter pinning the given width or height (the other dimension follows the source aspect ratio). */
  def scaleFilter(width: Option[Int], height: Option[Int]): Option[String] =
    width.map(w => s"scale=$w:-2").orElse(height.map(h => s"scale=-2:$h"))

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def stripExtension(path: Path): Path =
    val fileName = path.getFileName.toString
    val dotIdx   = fileName.lastIndexOf('.')
    if dotIdx >= 0 then path.resolveSibling(fileName.substring(0, dotIdx)) else path

  def stripExtension(fileName: String): String =
    val dotIdx = fileName.lastIndexOf('.')
    if dotIdx >= 0 then fileName.substring(0, dotIdx) else fileName

  /**
   * Renders an input URI as an ffmpeg/ffprobe `-i` argument. `file` URIs become plain filesystem paths, so
   * behavior for local files is unchanged; anything else (https, e.g. S3 presigned URLs) is passed through
   * untouched, letting ffmpeg stream it directly.
   */
  def inputArg(input: URI): String =
    if input.getScheme == "file" then Paths.get(input).toAbsolutePath.normalize.toString
    else input.toString

  /** Last segment of the URI path, e.g. the file name of a local path or the object key of a presigned URL. */
  def fileNameOf(input: URI): String =
    Option(input.getPath).flatMap(_.split("/").lastOption).filter(_.nonEmpty).getOrElse("input")

  def ffprobeVersion(using runner: ProcessRunner): IO[FFProbeVersion] =
    runner.useProcessOutput(
      "ffprobe-version",
      Command("ffprobe", List("-print_format", "json", "-show_program_version", "-loglevel", "quiet")),
      useErrorStream = false,
      runner.settings.ffprobeTimeout
    ) { stdout =>
      val result =
        for
          json    <- io.circe.parser.parse(stdout)
          version <- json.asObject.flatMap(_.apply("program_version")).toRight(new Exception("No program_version found"))
          decoded <- version.as[FFProbeVersion]
        yield decoded
      IO.pure(result.toTry.get)
    }

  def ffprobe(input: URI, timeout: FiniteDuration)(using runner: ProcessRunner): IO[(FFProbeOutput, Json)] =
    ffprobeVersion.flatMap { version =>
      val fileName = inputArg(input)
      val args     = List("-print_format", "json", "-show_streams", "-show_format", "-loglevel", "quiet", fileName)

      runner.useProcess("ffprobe-show", Command("ffprobe", args), timeout) { process =>
        runner.compileToString(process.stdout).map { jsonOutput =>
          (for
            json    <- io.circe.parser.parse(jsonOutput)
            streams <- json.asObject.flatMap(_.apply("streams")).toRight(new Exception("No streams found"))
            decoded <- streams.as[List[Stream]]
          yield FFProbeOutput(Some(version), Some(decoded)) -> json).toTry.get
        }
      }
    }

  def ffprobe(input: URI)(using runner: ProcessRunner): IO[(FFProbeOutput, Json)] =
    ffprobe(input, runner.settings.ffprobeTimeout)

  def addFastStart(video: URI, format: ContainerFormat, outputFile: Path)(using runner: ProcessRunner): IO[Path] =

    // MP4 faststart moves the moov atom to the front, Matroska/WebM moves the Cues (its index) to the front.
    val containerArgs = format match
      case ContainerFormat.Mp4      => List("-f", format.fmt, "-movflags", "+faststart")
      case ContainerFormat.Webm     => List("-f", format.fmt, "-cues_to_front", "1")
      case ContainerFormat.Matroska => List("-f", format.fmt, "-cues_to_front", "1")

    // Data streams (for example QuickTime timecode tracks) have no codec a target muxer can copy, so drop them.
    val args = List("-v", "error", "-i", inputArg(video), "-c", "copy", "-map", "0", "-map", "-0:d") ++ containerArgs ++ List(
      "-y",
      outputFile.toAbsolutePath.normalize.toString
    )

    runner.runIgnoreOutput("ffmpeg-add-faststart", Command("ffmpeg", args), runner.settings.remuxTimeout).map(_ => outputFile)

  def createThumbnail(
    input: URI,
    timestamp: Long,
    outputFile: Path,
    scaleWidth: Option[Int]  = None,
    scaleHeight: Option[Int] = None,
    codecArgs: List[String]  = Nil
  )(using runner: ProcessRunner): IO[Int] =

    val output = outputFile.toAbsolutePath.normalize.toString

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       inputArg(input)
    ) ++ scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runner.runIgnoreOutput("ffmpeg-create-video-thumbnail", Command("ffmpeg", args), runner.settings.thumbnailTimeout)

  /** Encodes a still image at the given pinned dimension; the output format follows the output extension. */
  def resizeImage(
    input: URI,
    outputFile: Path,
    width: Option[Int]      = None,
    height: Option[Int]     = None,
    codecArgs: List[String] = Nil
  )(using runner: ProcessRunner): IO[Int] = {
    val output = outputFile.toAbsolutePath.normalize.toString

    // format: off
    val args = List("-i", inputArg(input)) ++
      scaleFilter(width, height).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runner.runIgnoreOutput("ffmpeg-create-image-thumbnail", Command("ffmpeg", args), runner.settings.thumbnailTimeout)
  }

  def streamThumbnail(input: URI, timestamp: Long, scaleHeight: Int)(using runner: ProcessRunner): fs2.Stream[IO, Byte] = {

    // format: off
    val args = List(
      "-ss", formatTime(timestamp),
      "-i", inputArg(input),
      "-vcodec", "webp",
      "-vf", s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-v", "error",
      "-f", "image2pipe",
      "-"
    )
    // format: on

    runner.streamProcess("ffmpeg-stream-video-thumbnail", Command("ffmpeg", args), runner.settings.thumbnailTimeout)(_.stdout)
  }

  /**
   * Generates one storyboard sprite for the given window, holding a frame every `intervalInMillis`
   * and tiled into `columns` columns with as many rows as the frame count needs. The window is
   * seeked to directly, so a sprite for a later part of a long video does not require decoding
   * everything before it.
   *
   * When hardware decoding is available it is tried first and the job falls back to software for a
   * codec or device the GPU cannot handle, so an unsupported input costs a failed process start
   * rather than a missing preview. Coarse intervals additionally decode only keyframes, which is
   * where most of the work goes for long videos.
   */
  def createThumbnailTile(
    input: URI,
    outputFile: Path,
    tile: ThumbnailTile,
    overwrite: Boolean = false
  )(using runner: ProcessRunner): IO[Unit] =

    if Files.exists(outputFile) && !overwrite then IO.unit
    else {
      val window = tile.frameCount.toLong * tile.intervalInMillis
      val output = outputFile.toAbsolutePath.normalize.toString

      def command(hardware: Option[HardwareDecoding]): Command = Command(
        "ffmpeg",
        ThumbnailTile.decodeArgs(tile, hardware) ++ List(
          "-ss",
          formatTime(tile.startInMillis),
          "-t",
          formatTime(window),
          "-i",
          inputArg(input),
          "-vf",
          ThumbnailTile.filter(tile),
          "-frames:v",
          "1",
          "-quality",
          tile.quality.toString,
          "-v",
          "error",
          "-y",
          "-an",
          output
        )
      )

      /** Runs one ffmpeg attempt and rejects one that reports success without writing a sprite. */
      def attempt(name: String, hardware: Option[HardwareDecoding]): IO[Unit] =
        runner.runIgnoreOutput(name, command(hardware), runner.settings.tileTimeout).void >>
          IO.blocking(Files.size(outputFile)).flatMap { size =>
            IO.raiseWhen(size <= 0)(new IllegalStateException(s"ffmpeg produced an empty sprite: $output"))
          }

      runner.hardwareDecoding match
        case None         => attempt("ffmpeg-create-thumbnail-tile", None)
        case Some(config) =>
          attempt("ffmpeg-create-thumbnail-tile-hw", Some(config)).handleErrorWith { error =>
            logger.warn(s"Hardware decoding failed for '$input', falling back to software: ${error.getMessage}")
            attempt("ffmpeg-create-thumbnail-tile", None)
          }
    }

  def transcodeToMp4(
    input: URI,
    range: (Long, Long),
    includeAudio: Boolean     = true,
    profile: TranscodeProfile = TranscodeProfile.default,
    scaleWidth: Option[Int]   = None,
    scaleHeight: Option[Int]  = None,
    outputFile: Path
  )(using runner: ProcessRunner): IO[Path] = {

    val (ss, to) = range
    val output   = outputFile.toAbsolutePath.normalize.toString

    val args: List[String] = List("-ss", formatTime(ss), "-to", formatTime(to), "-i", inputArg(input)) ++
      scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++
      profile.args ++ Option.when(!includeAudio)("-an") ++
      List("-v", "quiet", "-y", output)

    runner.runIgnoreOutput("ffmpeg-transcode", Command("ffmpeg", args), runner.settings.clipTimeout).map(_ => Path.of(output))
  }

  def streamStranscodeMp4(input: URI, scaleHeight: Option[Int] = None)(using runner: ProcessRunner): fs2.Stream[IO, Byte] = {

    // format: off
    val args: List[String] =
      List("-i", inputArg(input)) ++
        scaleHeight.toList.flatMap(height => List("-vf", s"scale=-2:$height")) ++
        List(
          "-c:v", "libx264",
          "-movflags", "+faststart+frag_keyframe+empty_moov",
          "-crf", "23",
          "-v", "error",
          "-f", "mp4",
          "-an", // no audio
          "pipe:1"
        )
    // format: on

    runner.streamProcess("ffmpeg-stream-transcode", Command("ffmpeg", args), runner.settings.clipTimeout)(_.stdout)
  }
}
