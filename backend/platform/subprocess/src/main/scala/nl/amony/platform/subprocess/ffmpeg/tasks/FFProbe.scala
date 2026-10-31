package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.circe.{Decoder, Json}
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.tasks.FFProbeModel.{*, given}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

trait FFProbe extends Logging:

  self: ProcessRunner =>

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern = raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  lazy val ffprobeVersion: IO[FFProbeVersion] =
    useProcessOutput(
      "ffprobe-version",
      Command("ffprobe", List("-print_format", "json", "-show_program_version", "-loglevel", "quiet")),
      useErrorStream = false,
      settings.ffprobeTimeout
    ) { stdout =>
      val result =
        for
          json    <- io.circe.parser.parse(stdout)
          version <- json.asObject.flatMap(_.apply("program_version")).toRight(new Exception("No program_version found"))
          decoded <- version.as[FFProbeVersion]
        yield decoded
      IO.pure(result.toTry.get)
    }.memoize.flatten

  def ffprobe(input: URI, debug: Boolean, timeout: FiniteDuration = settings.ffprobeTimeout): IO[(FFProbeOutput, Json)] =
    runFFProbe(input, debug, timeout).recoverWith {
      case error if debug =>
        logger.warn(
          s"ffprobe with debug output failed for '$input', retrying without debug output (faststart info will be missing)",
          error
        )
        runFFProbe(input, debug = false, timeout)
    }

  private def runFFProbe(input: URI, debug: Boolean, timeout: FiniteDuration): IO[(FFProbeOutput, Json)] =
    ffprobeVersion.flatMap { version =>
      val fileName = FFMpeg.inputArg(input)
      val v        = if debug then "debug" else "quiet"
      val args     = List("-print_format", "json", "-show_streams", "-show_format", "-loglevel", v, fileName)

      useProcess("ffprobe-show", Command("ffprobe", args), timeout) { process =>
        for
          jsonOutput  <- compileToString(process.stdout)
          debugOutput <-
            if debug then compileToString(process.stderr).map(debugOutput => Some(ProbeDebugOutput(fastStartPattern.matches(debugOutput))))
            else IO.pure(None)
        yield {

          (for
            json    <- io.circe.parser.parse(jsonOutput)
            streams <- json.asObject.flatMap(_.apply("streams")).toRight(new Exception("No streams found"))
            decoded <- streams.as[List[Stream]]
          yield FFProbeOutput(Some(version), Some(decoded), debugOutput) -> json).toTry.get
        }
      }
    }
