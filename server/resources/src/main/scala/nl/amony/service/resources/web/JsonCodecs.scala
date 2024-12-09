package nl.amony.service.resources.web

import io.circe.Encoder
import nl.amony.service.resources.api.{ImageMeta, ResourceInfo, ResourceMeta, VideoMeta}
import nl.amony.service.resources.web.ResourceWebModel.*

object JsonCodecs {
  
  // contra map encoders for internal protocol classes
  given resourceInfoEncoder: Encoder[ResourceInfo] =
    summon[Encoder[ResourceDto]].contramap[ResourceInfo](toDto)

  def toDto(resource: ResourceInfo): ResourceDto = {

    val resolutions: List[Int] = (resource.height :: List(352)).sorted

    val thumbnailTimestamp: Long = resource.thumbnailTimestamp.getOrElse(resource.durationInMillis() / 3)

    val urls = {

      val tsPart = if (thumbnailTimestamp != 0) s"_${thumbnailTimestamp}" else ""

      ResourceUrlsDto(
        originalResourceUrl  = s"/resources/${resource.bucketId}/${resource.hash}.mp4",
        thumbnailUrl         = s"/resources/${resource.bucketId}/${resource.hash}${tsPart}_${resolutions.min}p.webp",
        previewThumbnailsUrl = Some(s"/resources/${resource.bucketId}/${resource.hash}-timeline.vtt")
      )
    }

    val meta = UserMetaDto(
      title       = resource.title.orElse(Some(resource.fileName())),
      description = resource.description,
      tags        = resource.tags.toList
    )

    val mediaInfo: ResourceMetaDto = resource.contentMeta match {
      case ImageMeta(width, height, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = 0,
            fps       = 0,
            codec     = None,
          )

      case VideoMeta(width, height, fps, duration, codec, _) =>
          ResourceMetaDto(
            width     = width,
            height    = height,
            duration  = duration,
            fps       = fps,
            codec     = codec,
          )

      case ResourceMeta.Empty =>
          ResourceMetaDto(
            width     = 0,
            height    = 0,
            duration  = 0,
            fps       = 0,
            codec     = None,
          )
    }

    val resourceInfo = ResourceInfoDto(
      sizeInBytes = resource.size,
      hash        = resource.hash,
      path        = resource.path
    )

    // hard coded for now
    val range = (thumbnailTimestamp, Math.min(mediaInfo.duration, thumbnailTimestamp + 3000))
    val highlights = List(FragmentDto(resource.hash, 0, range, List.empty, None, List.empty))

    ResourceDto(
      bucketId  = resource.bucketId,
      resourceId = resource.hash,
      uploader  = "0",
      uploadTimestamp = resource.getCreationTime,
      urls = urls,
      userMeta = meta,
      contentType = resource.contentType.getOrElse("unknown"),
      resourceMeta = mediaInfo,
      resourceInfo = resourceInfo,
      thumbnailTimestamp = resource.thumbnailTimestamp,
      highlights = {
        highlights.zipWithIndex.map { case (f, index) =>

          val (start, end) = f.range

          val urls = resolutions.map(height =>
            s"/resources/${resource.bucketId}/${resource.hash}~${start}-${end}_${height}p.mp4"
          )

          FragmentDto(
            resourceId = resource.hash,
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
