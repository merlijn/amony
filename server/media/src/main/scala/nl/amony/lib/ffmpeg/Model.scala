package nl.amony.lib.ffmpeg

object Model {

  case class ProbeDebugOutput(isFastStart: Boolean)

  case class ProbeOutput(streams: List[Stream], debugOutput: Option[ProbeDebugOutput]) {
    def firstVideoStream: Option[VideoStream] =
      streams.sortBy(_.index).collectFirst { case v: VideoStream => v }
  }

  val durationPattern = raw"(\d{2}):(\d{2}):(\d{2})".r.unanchored

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
      duration: String,
      bit_rate: Option[String],
      avg_frame_rate: String,
      tags: Option[Map[String, String]]
  ) extends Stream {
    def durationMillis: Long = (duration.toDouble * 1000L).toLong
    def fps: Double = {
      val splitted = avg_frame_rate.split('/')
      val divident = splitted(0).toDouble
      val divisor  = splitted(1).toDouble

      divident / divisor
    }
  }
}
