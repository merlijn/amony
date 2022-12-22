package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg.fastStartPattern
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.{ProbeDebugOutput, ProbeOutput}
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}

trait FFProbe extends Logging with FFProbeJsonCodecs {

  self: ProcessRunner =>

  val defaultProbeTimeout = 5.seconds

  def ffprobe(file: Path, debug: Boolean, timeout: FiniteDuration = defaultProbeTimeout): IO[ProbeOutput] = {

    val fileName = file.toAbsolutePath.normalize().toString

    val v    = if (debug) "debug" else "quiet"
    val args = List("-print_format", "json", "-show_streams", "-loglevel", v, fileName)

    runCmd("ffprobe" :: args) { process =>
      IO {
        val jsonOutput = scala.io.Source.fromInputStream(process.getInputStream).mkString

        // setting -v to debug will hang the standard output stream on some files.
        val debugOutput = {
          if (debug) {
            val debugOutput = scala.io.Source.fromInputStream(process.getErrorStream).mkString
            val fastStart   = fastStartPattern.matches(debugOutput)
            Some(ProbeDebugOutput(fastStart))
          } else {
            None
          }
        }

        io.circe.parser.decode[ProbeOutput](jsonOutput) match {
          case Left(error) => throw error
          case Right(out)  => out.copy(debugOutput = debugOutput)
        }
      }.timeout(timeout)
    }
  }
}
