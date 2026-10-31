package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.circe.Json

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.tasks.FFProbeModel.{*, given}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

trait FFProbe:

  self: ProcessRunner =>

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

  def ffprobe(input: URI, timeout: FiniteDuration = settings.ffprobeTimeout): IO[(FFProbeOutput, Json)] =
    ffprobeVersion.flatMap { version =>
      val fileName = FFMpeg.inputArg(input)
      val args     = List("-print_format", "json", "-show_streams", "-show_format", "-loglevel", "quiet", fileName)

      useProcess("ffprobe-show", Command("ffprobe", args), timeout) { process =>
        compileToString(process.stdout).map { jsonOutput =>
          (for
            json    <- io.circe.parser.parse(jsonOutput)
            streams <- json.asObject.flatMap(_.apply("streams")).toRight(new Exception("No streams found"))
            decoded <- streams.as[List[Stream]]
          yield FFProbeOutput(Some(version), Some(decoded)) -> json).toTry.get
        }
      }
    }
