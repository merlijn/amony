package nl.amony.platform.subprocess.ffmpeg.tasks

import scala.util.matching.UnanchoredRegex

import io.circe.*
import io.circe.generic.semiauto.deriveDecoder
import scribe.Logging

object FFProbeModel extends Logging {

  case class FFProbeVersion(version: String, copyright: String, compiler_ident: String, configuration: String) derives Decoder

  case class FFProbeOutput(program_version: Option[FFProbeVersion], streams: Option[List[Stream]]) derives Decoder {
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

  /** An entry of ffprobe's `side_data_list`, of which the display matrix carries the rotation. */
  case class SideData(rotation: Option[Int]) derives Decoder

  case class VideoStream(
    override val index: Int,
    codec_name: String,
    width: Int,
    height: Int,
    sample_aspect_ratio: Option[String],
    duration: Option[String],
    bit_rate: Option[String],
    avg_frame_rate: String,
    tags: Option[Map[String, String]],
    side_data_list: Option[List[SideData]]
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

    /** Clockwise display rotation, read from the display matrix or the legacy `rotate` tag. */
    def rotationDegrees: Int = {
      val rotation = side_data_list
        .toList
        .flatten
        .flatMap(_.rotation)
        .headOption
        .orElse(tags.flatMap(_.get("rotate")).flatMap(_.trim.toDoubleOption.map(_.round.toInt)))
        .getOrElse(0)

      ((rotation % 360) + 360) % 360
    }

    /** Non-square pixel ratio, e.g. 32:27 for anamorphic DVD video. None when the pixels are square. */
    def pixelAspectRatio: Option[Double] =
      sample_aspect_ratio
        .flatMap { value =>
          value.split(':') match
            case Array(widthPart, heightPart) =>
              for
                sarWidth  <- widthPart.trim.toDoubleOption
                sarHeight <- heightPart.trim.toDoubleOption
                if sarWidth > 0 && sarHeight > 0
              yield sarWidth / sarHeight
            case _                            => None
        }
        .filter(ratio => math.abs(ratio - 1.0) > 0.001)

    private def scaledWidth: Int = math.max(1, math.round(width * pixelAspectRatio.getOrElse(1.0)).toInt)

    /** Width after applying pixel aspect ratio and display rotation, as ffmpeg autorotates while decoding. */
    def displayWidth: Int = if rotationDegrees % 180 == 90 then height else scaledWidth

    /** Height after applying pixel aspect ratio and display rotation, as ffmpeg autorotates while decoding. */
    def displayHeight: Int = if rotationDegrees % 180 == 90 then scaledWidth else height
  }

  given unkownStreamDecoder: Decoder[UnkownStream] = deriveDecoder[UnkownStream]

  given videoStreamDecoder: Decoder[VideoStream] = deriveDecoder[VideoStream]

  given audioStreamDecoder: Decoder[AudioStream] = deriveDecoder[AudioStream]

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
