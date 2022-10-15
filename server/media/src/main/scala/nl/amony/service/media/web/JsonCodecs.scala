package nl.amony.service.media.web

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Encoder}
import nl.amony.service.fragments.FragmentProtocol
import nl.amony.service.resources.ResourceConfig.TranscodeSettings
import nl.amony.service.fragments.WebModel.Fragment
import nl.amony.service.media.web.MediaWebModel._
import nl.amony.service.media.{MediaProtocol => protocol}

class JsonCodecs(transcodingSettings: List[TranscodeSettings]) {

  // web model codecs
  implicit val createFragmentCodec: Codec[Range]         = deriveCodec[Range]
  implicit val mediaInfoCodec: Codec[MediaInfo]          = deriveCodec[MediaInfo]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val urlsCodec: Codec[MediaUrls]               = deriveCodec[MediaUrls]
  implicit val codec: Codec[ResourceInfo]                = deriveCodec[ResourceInfo]
  implicit val videoMetaCodec: Codec[MediaMeta]          = deriveCodec[MediaMeta]

  // contra map encoders for internal protocol classes
  implicit val mediaEncoder: Encoder[protocol.Media] =
    deriveEncoder[Video].contramapObject[protocol.Media](toWebModel)

  def toWebModel(media: protocol.Media): Video = {

    val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

    val urls = MediaUrls(
      originalResourceUrl  = s"/resources/media/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}_${media.height}p.${media.resourceInfo.extension}",
      thumbnailUrl         = s"/resources/media/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}-${media.thumbnailTimestamp}_${resolutions.min}p.webp",
      previewThumbnailsUrl = Some(s"/resources/media/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}-timeline.vtt")
    )

    val meta = MediaMeta(
      title   = media.meta.title.orElse(Some(media.fileName())),
      comment = media.meta.comment,
      tags    = media.meta.tags.toList
    )

    val mediaInfo = MediaInfo(
      width     = media.width,
      height    = media.height,
      duration  = media.mediaInfo.duration,
      fps       = media.mediaInfo.fps,
      codecName = media.mediaInfo.videoCodec,
    )

    val resourceInfo = ResourceInfo(
      sizeInBytes = media.resourceInfo.size,
      hash = media.resourceInfo.hash
    )

    // hard coded for now
    val start = (media.mediaInfo.duration / 3)
    val range = (start, Math.min(media.mediaInfo.duration, start + 3000))
    val highlights = List(FragmentProtocol.Fragment(media.id, range, None, List.empty))

    Video(
      id        = media.id,
      uploader  = media.uploader,
      uploadTimestamp = media.uploadTimestamp,
      urls = urls,
      meta = meta,
      mediaInfo = mediaInfo,
      resourceInfo = resourceInfo,
      highlights = {
        highlights.zipWithIndex.map { case (f, index) =>

          val (start, end) = f.range

          val urls = resolutions.map(height =>
            s"/resources/media/${media.resourceInfo.bucketId}/${media.id}~${start}-${end}_${height}p.mp4"
          )

          Fragment(
            media_id = media.id,
            index    = index,
            range    = (start, end),
            urls     = urls,
            comment  = f.comment,
            tags     = f.tags
          )
        }
      }
    )
  }
}
