package nl.amony.service.resources.web

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Encoder}
import nl.amony.service.resources.web.ResourceWebModel._
import nl.amony.service.resources.api.{ImageMeta, VideoMeta}

object JsonCodecs {

  // web model codecs
  implicit val fragmentCodec: Codec[Fragment]            = deriveCodec[Fragment]
  implicit val mediaInfoCodec: Codec[ResourceMeta]          = deriveCodec[ResourceMeta]
  implicit val videoCodec: Codec[Media]                  = deriveCodec[Media]
  implicit val urlsCodec: Codec[ResourceUrls]               = deriveCodec[ResourceUrls]
  implicit val codec: Codec[ResourceInfo]                = deriveCodec[ResourceInfo]
  implicit val videoMetaCodec: Codec[UserMeta]           = deriveCodec[UserMeta]

  // contra map encoders for internal protocol classes
  implicit val mediaEncoder: Encoder[nl.amony.service.resources.api.ResourceInfo] =
    deriveEncoder[Media].contramapObject[nl.amony.service.resources.api.ResourceInfo](toWebModel)

  def toWebModel(resource: nl.amony.service.resources.api.ResourceInfo): Media = {

    val resolutions: List[Int] = (resource.height :: List.empty).sorted

    val urls = {

      val thumbnailTimestamp = resource.durationInMillis() / 3

      val tsPart = if (thumbnailTimestamp != 0) s"_${thumbnailTimestamp}" else ""

      ResourceUrls(
        originalResourceUrl  = s"/resources/${resource.bucketId}/${resource.hash}.mp4",
        thumbnailUrl         = s"/resources/${resource.bucketId}/${resource.hash}${tsPart}_${resolutions.min}p.webp",
        previewThumbnailsUrl = Some(s"/resources/${resource.bucketId}/${resource.hash}-timeline.vtt")
      )
    }

    val meta = UserMeta(
      title   = Some(resource.fileName()),
      comment = None,
      tags    = resource.tags.toList
    )

    val mediaInfo: ResourceMeta = resource.contentMeta match {
      case ImageMeta(contentType, width, height, _) =>
          ResourceMeta(
            width     = width,
            height    = height,
            duration  = 0,
            fps       = 0,
            mediaType = contentType,
          )

      case VideoMeta(contentType, width, height, fps, duration, _) =>
          ResourceMeta(
            width     = width,
            height    = height,
            duration  = duration,
            fps       = fps,
            mediaType = contentType,
          )
    }

    val resourceInfo = ResourceInfo(
      sizeInBytes = resource.size,
      hash = resource.hash
    )

    // hard coded for now
    val start = (mediaInfo.duration / 3)
    val range = (start, Math.min(mediaInfo.duration, start + 3000))
    val highlights = List(Fragment(resource.hash, 0, range, List.empty, None, List.empty))

    Media(
      id        = resource.hash,
      uploader  = "0",
      uploadTimestamp = resource.getCreationTime,
      urls = urls,
      meta = meta,
      mediaInfo = mediaInfo,
      resourceInfo = resourceInfo,
      highlights = {
        highlights.zipWithIndex.map { case (f, index) =>

          val (start, end) = f.range

          val urls = resolutions.map(height =>
            s"/resources/${resource.bucketId}/${resource.hash}~${start}-${end}_${height}p.mp4"
          )

          Fragment(
            media_id = resource.hash,
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
