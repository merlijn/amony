package nl.amony.lib.ffmpeg

import io.circe.{Decoder, HCursor}
import io.circe.generic.semiauto.deriveDecoder
import nl.amony.lib.ffmpeg.Model._
import scribe.Logging

private [ffmpeg] trait FFMpegJsonCodecs extends Logging {
  implicit val unkownStreamDecoder: Decoder[UnkownStream] = deriveDecoder[UnkownStream]
  implicit val videoStreamDecoder: Decoder[VideoStream] = deriveDecoder[VideoStream]
  implicit val audioStreamDecoder: Decoder[AudioStream] = deriveDecoder[AudioStream]
  implicit val debugDecoder: Decoder[ProbeDebugOutput] = deriveDecoder[ProbeDebugOutput]
  implicit val probeDecoder: Decoder[ProbeOutput]       = deriveDecoder[ProbeOutput]

  implicit val streamDecoder: Decoder[Stream] = new Decoder[Stream] {
    final def apply(c: HCursor): Decoder.Result[Stream] = {
      c.downField("codec_type").as[String].flatMap {
        case "video" => c.as[VideoStream]
        case "audio" => c.as[AudioStream]
        case _       => c.as[UnkownStream]
      }.left.map(error => {
        logger.warn(s"Failed to decode stream: ${c.value}")
        error
      })
    }
  }
}
