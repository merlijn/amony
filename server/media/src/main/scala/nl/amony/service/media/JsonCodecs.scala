package nl.amony.service.media

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Encoder}
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.MediaWebModel._
import nl.amony.service.media.actor.{ MediaLibProtocol => protocol }

class JsonCodecs(transcodingSettings: List[TranscodeSettings]) {

  // web model codecs
  implicit val fragmentCodec: Codec[Fragment]            = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[Range]         = deriveCodec[Range]
  implicit val mediaInfoCodec: Codec[MediaInfo]          = deriveCodec[MediaInfo]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val urlsCodec: Codec[MediaUrls]               = deriveCodec[MediaUrls]
  implicit val codec: Codec[ResourceInfo]                = deriveCodec[ResourceInfo]
  implicit val videoMetaCodec: Codec[MediaMeta]          = deriveCodec[MediaMeta]

  // contra map encoders for internal protocol classes
  implicit val mediaEncoder: Encoder[protocol.Media] =
    deriveEncoder[Video].contramapObject[protocol.Media](toWebModel)

  def toWebModel(mediaId: String, f: protocol.Fragment): Fragment = {

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

  def toWebModel(media: protocol.Media): Video = {

    val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

    val urls = MediaUrls(
      originalResourceUrl  = s"/resources/media/${media.id}_${media.height}p.${media.fileInfo.extension}",
      thumbnailUrl         = s"/resources/media/${media.id}_${resolutions.min}p.webp",
      previewThumbnailsUrl = Some(s"/resources/media/${media.id}-timeline.vtt")
    )

    val meta = MediaMeta(
      title   = media.meta.title.orElse(Some(media.fileName())),
      comment = media.meta.comment,
      tags    = media.meta.tags.toList
    )

    val mediaInfo = MediaInfo(
      width  = media.width,
      height = media.height,
      duration               = media.videoInfo.duration,
      fps                    = media.videoInfo.fps,
      codecName = media.videoInfo.videoCodec,
    )

    val resourceInfo = ResourceInfo(
      sizeInBytes = media.fileInfo.size,
      hash = media.fileInfo.hash
    )

    Video(
      id        = media.id,
      uploader  = media.uploader,
      uploadTimestamp = media.uploadTimestamp,
      urls = urls,
      meta = meta,
      mediaInfo = mediaInfo,
      resourceInfo = resourceInfo,
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
      }
    )
  }
}
