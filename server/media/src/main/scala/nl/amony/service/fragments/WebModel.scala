package nl.amony.service.fragments

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import nl.amony.service.fragments
import nl.amony.service.resources.ResourceConfig.TranscodeSettings

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

    def toWebModel(transcodingSettings: List[TranscodeSettings], f: fragments.Protocol.Fragment): Fragment = {

      val (start, end) = f.range

      val resolutions = transcodingSettings.map(_.scaleHeight).sorted
      val urls =
        resolutions.map(height => s"/resources/test/${f.mediaId}~${start}-${end}_${height}p.mp4")

      Fragment(
        media_id = f.mediaId,
        index = 0,
        range = (start, end),
        urls = urls,
        comment = f.comment,
        tags = f.tags
      )
    }
  }
}
