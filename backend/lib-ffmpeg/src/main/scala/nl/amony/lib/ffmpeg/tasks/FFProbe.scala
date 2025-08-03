package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import io.circe.{Decoder, HCursor, Json}
import nl.amony.lib.ffmpeg.FFMpeg.fastStartPattern
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Try}
import FFProbeModel.{*, given}

trait FFProbe extends Logging {

  self: ProcessRunner =>

  val defaultProbeTimeout = 5.seconds

  val ffprobeVersion: IO[FFProbeVersion]  =
    useProcessOutput("ffprobe", List("-print_format", "json", "-show_program_version", "-loglevel", "quiet"), false) { stdout =>
      val result = for {
        json     <- io.circe.parser.parse(stdout)
        version  <- json.asObject.flatMap(_.apply("program_version")).toRight(new Exception("No program_version found"))
        decoded  <- version.as[FFProbeVersion]
      } yield decoded
      IO.pure(result.toTry.get)
    }.timeout(defaultProbeTimeout).memoize.flatten

  def ffprobe(jsonString: String) = IO[FFProbeOutput] {
    val json = io.circe.parser.parse(jsonString).toTry.get
    val result = for {
      decoded <- json.as[FFProbeOutput]
    } yield decoded
    result.toTry.get
  }
  
  def ffprobe(file: Path, debug: Boolean, timeout: FiniteDuration = defaultProbeTimeout): IO[(FFProbeOutput, Json)] =
    ffprobeVersion.flatMap { version =>
      val fileName = file.toAbsolutePath.normalize().toString
      val v    = if (debug) "debug" else "quiet"
      val args = List("-print_format", "json", "-show_streams", "-show_format", "-loglevel", v, fileName)

      useProcess("ffprobe", args) { process =>

        for {
          jsonOutput  <- toString(process.stdout)
          debugOutput <- if (debug) toString(process.stderr).map(debugOutput => Some(ProbeDebugOutput(fastStartPattern.matches(debugOutput)))) else IO.pure(None)
        } yield {

          (for {
            json     <- io.circe.parser.parse(jsonOutput)
            streams  <- json.asObject.flatMap(_.apply("streams")).toRight(new Exception("No streams found"))
            decoded  <- streams.as[List[Stream]]
          } yield FFProbeOutput(Some(version), Some(decoded), debugOutput) -> json).toTry.get
        }
      }.timeout(timeout)
  }
}
