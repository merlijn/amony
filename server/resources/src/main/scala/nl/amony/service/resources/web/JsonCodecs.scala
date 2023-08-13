package nl.amony.service.resources.web

import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import nl.amony.service.resources.web.ResourceWebModel.*
import nl.amony.service.resources.api.{ImageMeta, VideoMeta}

object JsonCodecs {

  // web model codecs
  given fragmentCodec: Codec[Fragment]         = deriveCodec[Fragment]
  given mediaInfoCodec: Codec[ResourceMetaDto] = deriveCodec[ResourceMetaDto]
  given urlsCodec: Codec[ResourceUrls]         = deriveCodec[ResourceUrls]
  given codec: Codec[ResourceInfoDto]          = deriveCodec[ResourceInfoDto]
  given videoMetaCodec: Codec[UserMeta]        = deriveCodec[UserMeta]
  given resourceEncoder: Codec[ResourceDto]    = deriveCodec[ResourceDto]

  // contra map encoders for internal protocol classes
  given mediaEncoder: Encoder[nl.amony.service.resources.api.ResourceInfo] =
    resourceEncoder.contramap[nl.amony.service.resources.api.ResourceInfo](toWebModel)

  def toWebModel(resource: nl.amony.service.resources.api.ResourceInfo): ResourceDto = {

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
      title   = resource.title.orElse(Some(resource.fileName())),
      comment = resource.description,
      tags    = resource.tags.toList
    )

    val mediaInfo: ResourceMetaDto = resource.contentMeta match {
      case ImageMeta(contentType, width, height, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = 0,
            fps       = 0,
            mediaType = contentType,
          )

      case VideoMeta(contentType, width, height, fps, duration, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = duration,
            fps       = fps,
            mediaType = contentType,
          )
    }

    val resourceInfo = ResourceInfoDto(
      sizeInBytes = resource.size,
      hash = resource.hash
    )

    // hard coded for now
    val start = (mediaInfo.duration / 3)
    val range = (start, Math.min(mediaInfo.duration, start + 3000))
    val highlights = List(Fragment(resource.hash, 0, range, List.empty, None, List.empty))

    ResourceDto(
      bucketId  = resource.bucketId,
      id        = resource.hash,
      uploader  = "0",
      uploadTimestamp = resource.getCreationTime,
      urls = urls,
      userMeta = meta,
      resourceMeta = mediaInfo,
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
