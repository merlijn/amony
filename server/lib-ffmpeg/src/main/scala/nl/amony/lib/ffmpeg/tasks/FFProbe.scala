package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import io.circe.{Decoder, HCursor}
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.lib.ffmpeg.FFMpeg.fastStartPattern
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.*
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Try}
import FFProbeJsonCodecs.given

trait FFProbe extends Logging {

  self: ProcessRunner =>

  val defaultProbeTimeout = 5.seconds

  def ffprobe(file: Path, debug: Boolean, timeout: FiniteDuration = defaultProbeTimeout): IO[Try[FFProbeResult]] = {

    val fileName = file.toAbsolutePath.normalize().toString

    val v    = if (debug) "debug" else "quiet"
    val args = List("-print_format", "json", "-show_streams", "-loglevel", v, fileName)

    useProcess("ffprobe", args) { process =>

      for {
        jsonOutput  <- toString(process.stdout)
        debugOutput <- if (debug) toString(process.stderr).map(debugOutput => Some(ProbeDebugOutput(fastStartPattern.matches(debugOutput)))) else IO.pure(None)
      } yield {

        (for {
          json <- io.circe.parser.parse(jsonOutput)
          out  <- json.as[FFProbeOutput]
        } yield FFProbeResult(out, debugOutput, json)).toTry
      }
    }.timeout(timeout).recover {
      case e: java.util.concurrent.TimeoutException => Failure(e)
    }
  }
}
