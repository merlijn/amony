package nl.amony.lib.process.ffmpeg.tasks

import scala.util.matching.UnanchoredRegex

import io.circe.*
import io.circe.generic.semiauto.deriveDecoder
import scribe.Logging

object FFProbeModel extends Logging {

  case class ProbeDebugOutput(isFastStart: Boolean)

  case class FFProbeVersion(version: String, copyright: String, compiler_ident: String, configuration: String) derives Decoder

  case class FFProbeOutput(program_version: Option[FFProbeVersion], streams: Option[List[Stream]], debugOutput: Option[ProbeDebugOutput])
      derives Decoder {
    def firstVideoStream: Option[VideoStream] = streams.flatMap(_.sortBy(_.index).collectFirst { case v: VideoStream => v })
  }

  val durationPattern: UnanchoredRegex = raw"(\d{2}):(\d{2}):(\d{2})\.(\d*)".r.unanchored

  sealed trait Stream {
    val index: Int
    val codec_type: String = this match {
      case _: VideoStream => "video"
      case _: AudioStream => "audio"
      case _              => "unknown"
    }
  }

  case class UnkownStream(override val index: Int) extends Stream

  case class AudioStream(override val index: Int, codec_name: String) extends Stream

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

    def durationFromField: Option[Int]  = duration.map(d => (d.toDouble * 1000).toInt)
    def durationFromMkvTag: Option[Int] = tags.flatMap(_.get("DURATION")).flatMap {
      case durationPattern(hours, minutes, seconds, subseconds) =>
        Some(hours.toInt * 60 * 60 * 1000 + minutes.toInt * 60 * 1000 + seconds.toInt * 1000 + (s"0.$subseconds".toDouble * 1000).toInt)
      case _                                                    => None
    }

    def durationMillis: Int = durationFromField.orElse(durationFromMkvTag).getOrElse(0)
    def fps: Double         = {
      val splitted = avg_frame_rate.split('/')
      val divident = splitted(0).toDouble
      val divisor  = splitted(1).toDouble

      divident / divisor
    }
  }

  given unkownStreamDecoder: Decoder[UnkownStream] = deriveDecoder[UnkownStream]

  given videoStreamDecoder: Decoder[VideoStream] = deriveDecoder[VideoStream]

  given audioStreamDecoder: Decoder[AudioStream] = deriveDecoder[AudioStream]

  given debugDecoder: Decoder[ProbeDebugOutput] = deriveDecoder[ProbeDebugOutput]

  given streamDecoder: Decoder[Stream] = (c: HCursor) => {
    c.downField("codec_type").as[String].flatMap {
      case "video" => c.as[VideoStream]
      case "audio" => c.as[AudioStream]
      case _       => c.as[UnkownStream]
    }.left.map {
      error =>
        logger.warn(s"Failed to decode stream: ${c.value}", error)
        error
    }
  }

  given ffprobeOutputDecoder: Decoder[FFProbeOutput] = deriveDecoder[FFProbeOutput]
}
