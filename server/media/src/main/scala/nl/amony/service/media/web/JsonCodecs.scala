package nl.amony.service.media.web

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Encoder}
import nl.amony.service.media.web.MediaWebModel._
import nl.amony.service.media.api
import nl.amony.service.resources.api.{ImageMeta, VideoMeta}

object JsonCodecs {

  // web model codecs
  implicit val fragmentCodec: Codec[Fragment]            = deriveCodec[Fragment]
  implicit val mediaInfoCodec: Codec[MediaInfo]          = deriveCodec[MediaInfo]
  implicit val videoCodec: Codec[Media]                  = deriveCodec[Media]
  implicit val urlsCodec: Codec[MediaUrls]               = deriveCodec[MediaUrls]
  implicit val codec: Codec[ResourceInfo]                = deriveCodec[ResourceInfo]
  implicit val videoMetaCodec: Codec[MediaMeta]          = deriveCodec[MediaMeta]

  // contra map encoders for internal protocol classes
  implicit val mediaEncoder: Encoder[api.Media] =
    deriveEncoder[Media].contramapObject[api.Media](toWebModel)

  def toWebModel(media: api.Media): Media = {

    val resolutions = (media.height :: media.availableFormats.toList.map(_.scaleHeight)).sorted

    val extension = media.resourceInfo.relativePath.split('.').last

    val urls = {

      val tsPart = if (media.thumbnailTimestamp != 0) s"_${media.thumbnailTimestamp}" else ""

      MediaUrls(
        originalResourceUrl  = s"/resources/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}.mp4",
        thumbnailUrl         = s"/resources/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}${tsPart}_${resolutions.min}p.webp",
        previewThumbnailsUrl = Some(s"/resources/${media.resourceInfo.bucketId}/${media.resourceInfo.hash}-timeline.vtt")
      )
    }

    val meta = MediaMeta(
      title   = media.meta.title.orElse(Some(media.fileName())),
      comment = media.meta.comment,
      tags    = media.meta.tags.toList
    )

    val mediaInfo: MediaInfo = media.mediaInfo match {
      case ImageMeta(contentType, width, height, _) =>
          MediaInfo(
            width     = width,
            height    = height,
            duration  = 0,
            fps       = 0,
            mediaType = contentType,
          )

      case VideoMeta(contentType, width, height, fps, duration, _) =>
          MediaInfo(
            width     = width,
            height    = height,
            duration  = duration,
            fps       = fps,
            mediaType = contentType,
          )
    }

    val resourceInfo = ResourceInfo(
      sizeInBytes = media.resourceInfo.sizeInBytes,
      hash = media.resourceInfo.hash
    )

    // hard coded for now
    val start = (mediaInfo.duration / 3)
    val range = (start, Math.min(mediaInfo.duration, start + 3000))
    val highlights = List(Fragment(media.mediaId, 0, range, List.empty, None, List.empty))

    Media(
      id        = media.mediaId,
      uploader  = media.userId,
      uploadTimestamp = media.createdTimestamp,
      urls = urls,
      meta = meta,
      mediaInfo = mediaInfo,
      resourceInfo = resourceInfo,
      highlights = {
        highlights.zipWithIndex.map { case (f, index) =>

          val (start, end) = f.range

          val urls = resolutions.map(height =>
            s"/resources/${media.resourceInfo.bucketId}/${media.mediaId}~${start}-${end}_${height}p.mp4"
          )

          Fragment(
            media_id = media.mediaId,
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
