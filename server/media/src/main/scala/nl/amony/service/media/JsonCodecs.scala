package nl.amony.service.media

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Encoder}
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.MediaWebModel._
import nl.amony.service.media.actor.MediaLibProtocol

class JsonCodecs(transcodingSettings: List[TranscodeSettings]) {

  // web model codecs
  implicit val fragmentCodec: Codec[Fragment]            = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[Range] = deriveCodec[Range]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val videoMetaCodec: Codec[VideoMeta]          = deriveCodec[VideoMeta]

  // contra map encoders for internal classes
  implicit val mediaEncoder: Encoder[MediaLibProtocol.Media] =
    deriveEncoder[Video].contramapObject[MediaLibProtocol.Media](toWebModel)

  def toWebModel(mediaId: String, f: MediaLibProtocol.Fragment): Fragment = {

    val resolutions = transcodingSettings.map(_.scaleHeight).sorted
    val urls =
      resolutions.map(height => s"/resources/media/${mediaId}~${f.fromTimestamp}-${f.toTimestamp}_${height}p.mp4")

    Fragment(
      mediaId,
      0,
      Range(f.fromTimestamp, f.toTimestamp),
      urls,
      f.comment,
      f.tags
    )
  }

  def toWebModel(media: MediaLibProtocol.Media): Video = {

    val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

    Video(
      id        = media.id,
      video_url = s"/resources/media/${media.id}_${media.height}p.${media.fileInfo.extension}",
      meta = VideoMeta(
        title   = media.title.orElse(Some(media.fileName())),
        comment = media.comment,
        tags    = media.tags.toList
      ),
      duration               = media.videoInfo.duration,
      addedOn                = media.fileInfo.creationTime,
      fps                    = media.videoInfo.fps,
      size                   = media.fileInfo.size,
      thumbnail_url          = s"/resources/media/${media.id}_${resolutions.min}p.webp",
      preview_thumbnails_url = Some(s"/resources/media/${media.id}-timeline.vtt"),
      fragments = {
        media.fragments.zipWithIndex.map { case (f, index) =>
          val urls = resolutions.map(height =>
            s"/resources/media/${media.id}~${f.fromTimestamp}-${f.toTimestamp}_${height}p.mp4"
          )

          Fragment(
            media_id = media.id,
            index    = index,
            range    = Range(f.fromTimestamp, f.toTimestamp),
            urls     = urls,
            comment  = f.comment,
            tags     = f.tags
          )
        }
      },
      width  = media.width,
      height = media.height
    )
  }
}
