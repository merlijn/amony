package nl.amony.service.resources.web

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}
import nl.amony.service.resources.api.{ImageMeta, VideoMeta, ResourceMeta}
import nl.amony.service.resources.web.ResourceWebModel.*

object JsonCodecs {

  // web model codecs
  given fragmentCodec: Codec[FragmentDto]      = deriveCodec[FragmentDto]
  given mediaInfoCodec: Codec[ResourceMetaDto] = deriveCodec[ResourceMetaDto]
  given urlsCodec: Codec[ResourceUrlsDto]      = deriveCodec[ResourceUrlsDto]
  given codec: Codec[ResourceInfoDto]          = deriveCodec[ResourceInfoDto]
  given videoMetaCodec: Codec[UserMetaDto]     = deriveCodec[UserMetaDto]
  given resourceEncoder: Codec[ResourceDto]    = deriveCodec[ResourceDto]

  // contra map encoders for internal protocol classes
  given resourceInfoEncoder: Encoder[nl.amony.service.resources.api.ResourceInfo] =
    resourceEncoder.contramap[nl.amony.service.resources.api.ResourceInfo](toDto)

  def toDto(resource: nl.amony.service.resources.api.ResourceInfo): ResourceDto = {

    val resolutions: List[Int] = (resource.height :: List(320)).sorted

    val urls = {

      val thumbnailTimestamp = resource.durationInMillis() / 3

      val tsPart = if (thumbnailTimestamp != 0) s"_${thumbnailTimestamp}" else ""

      ResourceUrlsDto(
        originalResourceUrl  = s"/resources/${resource.bucketId}/${resource.hash}.mp4",
        thumbnailUrl         = s"/resources/${resource.bucketId}/${resource.hash}${tsPart}_${resolutions.min}p.webp",
        previewThumbnailsUrl = Some(s"/resources/${resource.bucketId}/${resource.hash}-timeline.vtt")
      )
    }

    val meta = UserMetaDto(
      title   = resource.title.orElse(Some(resource.fileName())),
      description = resource.description,
      tags    = resource.tags.toList
    )

    val mediaInfo: ResourceMetaDto = resource.contentMeta match {
      case ImageMeta(width, height, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = 0,
            fps       = 0,
          )

      case VideoMeta(width, height, fps, duration, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = duration,
            fps       = fps,
          )

      case ResourceMeta.Empty =>
          ResourceMetaDto(
            width     = 0,
            height    = 0,
            duration  = 0,
            fps       = 0,
          )
    }

    val resourceInfo = ResourceInfoDto(
      sizeInBytes = resource.size,
      hash = resource.hash
    )

    // hard coded for now
    val start = (mediaInfo.duration / 3)
    val range = (start, Math.min(mediaInfo.duration, start + 3000))
    val highlights = List(FragmentDto(resource.hash, 0, range, List.empty, None, List.empty))

    ResourceDto(
      bucketId  = resource.bucketId,
      id        = resource.hash,
      uploader  = "0",
      uploadTimestamp = resource.getCreationTime,
      urls = urls,
      userMeta = meta,
      contentType = resource.contentType.getOrElse("unknown"),
      resourceMeta = mediaInfo,
      resourceInfo = resourceInfo,
      highlights = {
        highlights.zipWithIndex.map { case (f, index) =>

          val (start, end) = f.range

          val urls = resolutions.map(height =>
            s"/resources/${resource.bucketId}/${resource.hash}~${start}-${end}_${height}p.mp4"
          )

          FragmentDto(
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
