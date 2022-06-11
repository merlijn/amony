package nl.amony.lib.ffmpeg.tasks

import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, HCursor}
import nl.amony.lib.ffmpeg.tasks.FFProbeModel._
import scribe.Logging

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
