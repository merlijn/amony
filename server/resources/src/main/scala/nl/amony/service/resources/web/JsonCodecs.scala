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
        originalResourceUrl  = s"/api/resources/${resource.bucketId}/${resource.hash}/content",
        thumbnailUrl         = s"/api/resources/${resource.bucketId}/${resource.hash}/thumb${tsPart}_${resolutions.min}p.webp",
        previewThumbnailsUrl = Some(s"/api/resources/${resource.bucketId}/${resource.hash}/timeline.vtt")
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
    val highlights = List(ClipDto(resource.hash, range, List.empty, None, List.empty))

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
      clips = {
        highlights.map { f =>

          val (start, end) = f.range
          val urls = resolutions.map(height =>
            s"/api/resources/${resource.bucketId}/${resource.hash}/clip_${start}-${end}_${height}p.mp4"
          )

          ClipDto(
            resourceId   = resource.hash,
            range        = (start, end),
            urls         = urls,
            description  = f.description,
            tags         = f.tags
          )
        }
      }
    )
  }
}
