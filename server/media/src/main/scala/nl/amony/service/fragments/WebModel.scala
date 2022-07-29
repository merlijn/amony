package nl.amony.service.fragments

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import nl.amony.service.fragments
import nl.amony.service.media.MediaConfig.TranscodeSettings

object WebModel {

  case class Fragment(
     media_id: String,
     index: Int,
     range: (Long, Long),
     urls: List[String],
     comment: Option[String],
     tags: List[String]
   )

  object Fragment {

    implicit val jsonCodec: Codec[Fragment] = deriveCodec

    def fromProtocol(fragment: Protocol.Fragment): Fragment = {
      null
    }

    def fromProtocol(transcodingSettings: List[TranscodeSettings], f: fragments.Protocol.Fragment): Fragment = {

      val resolutions = transcodingSettings.map(_.scaleHeight).sorted
      val urls =
        resolutions.map(height => s"/resources/test/${f.mediaId}~${f.start}-${f.end}_${height}p.mp4")

      Fragment(
        f.mediaId,
        0,
        (f.start, f.end),
        urls,
        f.comment,
        f.tags
      )
    }
  }
}
