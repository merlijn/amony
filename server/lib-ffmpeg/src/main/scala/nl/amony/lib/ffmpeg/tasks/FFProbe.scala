package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import io.circe.{Decoder, HCursor}
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.lib.ffmpeg.FFMpeg.fastStartPattern
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.{AudioStream, ProbeDebugOutput, ProbeOutput, Stream, UnkownStream, VideoStream}
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}

private[ffmpeg] trait FFProbeJsonCodecs extends Logging {
  implicit val unkownStreamDecoder: Decoder[UnkownStream] = deriveDecoder[UnkownStream]
  implicit val videoStreamDecoder: Decoder[VideoStream]   = deriveDecoder[VideoStream]
  implicit val audioStreamDecoder: Decoder[AudioStream]   = deriveDecoder[AudioStream]
  implicit val debugDecoder: Decoder[ProbeDebugOutput]    = deriveDecoder[ProbeDebugOutput]
  implicit val probeDecoder: Decoder[ProbeOutput]         = deriveDecoder[ProbeOutput]

  implicit val streamDecoder: Decoder[Stream] = (c: HCursor) => {
    c.downField("codec_type")
      .as[String]
      .flatMap {
        case "video" => c.as[VideoStream]
        case "audio" => c.as[AudioStream]
        case _ => c.as[UnkownStream]
      }
      .left
      .map(error => {
        logger.warn(s"Failed to decode stream: ${c.value}", error)
        error
      })
  }
}

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
