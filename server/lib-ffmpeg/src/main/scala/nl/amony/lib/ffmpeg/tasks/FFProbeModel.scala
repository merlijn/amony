package nl.amony.lib.ffmpeg.tasks

import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, HCursor, Json}
import nl.amony.lib.ffmpeg.tasks.FFProbeJsonCodecs.logger
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.{AudioStream, FFProbeOutput, ProbeDebugOutput, Stream, UnkownStream, VideoStream}
import scribe.Logging

import scala.util.matching.UnanchoredRegex

object FFProbeModel {

  case class ProbeDebugOutput(isFastStart: Boolean)

  case class FFProbeOutput(streams: List[Stream]) {
    def firstVideoStream: Option[VideoStream] = streams.sortBy(_.index).collectFirst { case v: VideoStream => v }
  }
  
  case class FFProbeResult(output: FFProbeOutput, debugOutput: Option[ProbeDebugOutput], rawJson: Json)

  val durationPattern: UnanchoredRegex = raw"(\d{2}):(\d{2}):(\d{2})\.(\d*)".r.unanchored

  sealed trait Stream {
    val index: Int
  }

  case class UnkownStream(override val index: Int) extends Stream

  case class AudioStream(
      override val index: Int,
      codec_name: String
  ) extends Stream

  case class VideoStream(
      override val index: Int,
      codec_name: String,
      width: Int,
      height: Int,
      duration: Option[String],
      bit_rate: Option[String],
      avg_frame_rate: String,
      tags: Option[Map[String, String]]
  ) extends Stream {
    
    def durationFromField: Option[Long] = duration.map(d => (d.toDouble * 1000L).toLong)
    def durationFromMkvTag: Option[Long] =
      tags.flatMap(_.get("DURATION")).flatMap {
        case durationPattern(hours, minutes, seconds, subseconds) =>
          Some(
            hours.toLong * 60 * 60 * 1000L +
              minutes.toLong * 60 * 1000L +
              seconds.toLong * 1000L +
              (s"0.$subseconds".toDouble * 1000L).toLong
          )
        case _ => None
      }

    def durationMillis: Long = durationFromField.orElse(durationFromMkvTag).getOrElse(0L)
    def fps: Double = {
      val splitted = avg_frame_rate.split('/')
      val divident = splitted(0).toDouble
      val divisor  = splitted(1).toDouble

      divident / divisor
    }
  }
}

object FFProbeJsonCodecs extends Logging {
  given unkownStreamDecoder: Decoder[UnkownStream] = deriveDecoder[UnkownStream]
  given videoStreamDecoder: Decoder[VideoStream]   = deriveDecoder[VideoStream]
  given audioStreamDecoder: Decoder[AudioStream]   = deriveDecoder[AudioStream]
  given debugDecoder: Decoder[ProbeDebugOutput]    = deriveDecoder[ProbeDebugOutput]

  given streamDecoder: Decoder[Stream] = (c: HCursor) => {
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

  given ffprobeOutputDecoder: Decoder[FFProbeOutput] = deriveDecoder[FFProbeOutput]
}
