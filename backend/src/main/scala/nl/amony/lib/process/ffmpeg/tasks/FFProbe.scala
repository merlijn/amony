package nl.amony.lib.process.ffmpeg.tasks

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.IO
import io.circe.{Decoder, Json}
import scribe.Logging

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.ffmpeg.tasks.FFProbeModel.{*, given}

trait FFProbe extends Logging:

  self: ProcessRunner =>

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern = raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  val defaultProbeTimeout = 5.seconds

  val ffprobeVersion: IO[FFProbeVersion] =
    useProcessOutput("ffprobe", List("-print_format", "json", "-show_program_version", "-loglevel", "quiet"), false) {
      stdout =>
        val result =
          for
            json    <- io.circe.parser.parse(stdout)
            version <- json.asObject.flatMap(_.apply("program_version")).toRight(new Exception("No program_version found"))
            decoded <- version.as[FFProbeVersion]
          yield decoded
        IO.pure(result.toTry.get)
    }.timeout(defaultProbeTimeout).memoize.flatten

  def ffprobe(file: Path, debug: Boolean, timeout: FiniteDuration = defaultProbeTimeout): IO[(FFProbeOutput, Json)] =
    ffprobeVersion.flatMap { version =>
      val fileName = file.toAbsolutePath.normalize().toString
      val v        = if debug then "debug" else "quiet"
      val args     = List("-print_format", "json", "-show_streams", "-show_format", "-loglevel", v, fileName)

      useProcess("ffprobe", args) { process =>
        for
          jsonOutput  <- toString(process.stdout)
          debugOutput <-
            if debug then toString(process.stderr).map(debugOutput => Some(ProbeDebugOutput(fastStartPattern.matches(debugOutput))))
            else IO.pure(None)
        yield {

          (for
            json    <- io.circe.parser.parse(jsonOutput)
            streams <- json.asObject.flatMap(_.apply("streams")).toRight(new Exception("No streams found"))
            decoded <- streams.as[List[Stream]]
          yield FFProbeOutput(Some(version), Some(decoded), debugOutput) -> json).toTry.get
        }
      }.timeout(timeout)
    }
