package io.amony.http

import io.amony.actor.MediaLibActor
import io.amony.http.WebModel.{Tag, Fragment, FragmentRange, SearchResult, Video}
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}

trait JsonCodecs {

  // web model codecs
  implicit val thumbnailCodec: Codec[Fragment]           = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[FragmentRange] = deriveCodec[FragmentRange]
  implicit val searchResultCodec: Codec[SearchResult]    = deriveCodec[SearchResult]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val tagCodec: Codec[Tag]                      = deriveCodec[Tag]

  // contra map encoders for internal classes
  implicit val mediaEncoder: Encoder[MediaLibActor.Media] =
    deriveEncoder[Video].contramapObject[MediaLibActor.Media](toWebModel)

  implicit val tagEncoder: Encoder[MediaLibActor.Collection] =
    deriveEncoder[Tag].contramapObject[MediaLibActor.Collection](c => Tag(c.id, c.title))

  implicit val searchResultEncoder: Encoder[MediaLibActor.SearchResult] =
    deriveEncoder[SearchResult].contramapObject[MediaLibActor.SearchResult](
      result => SearchResult(result.offset, result.total, result.items.map(m => toWebModel(m)))
    )

  def toWebModel(media: MediaLibActor.Media): Video =
    Video(
      id            = media.id,
      uri           = s"/files/videos/${media.uri}",
      title         = media.title.getOrElse(media.fileName()),
      duration      = media.duration,
      fps           = media.fps,
      thumbnail_uri = s"/files/thumbnails/${media.id}-${media.thumbnailTimestamp}.webp",
      fragments = media.fragments.zipWithIndex.map { case (p, index) =>
        Fragment(
          index           = index,
          timestamp_start = p.fromTimestamp,
          timestamp_end   = p.toTimestamp,
          uri             = s"/files/thumbnails/${media.id}-${p.fromTimestamp}-${p.toTimestamp}.mp4",
          comment         = p.comment,
          tags            = p.tags
        )

      },
      resolution_x = media.resolution._1,
      resolution_y = media.resolution._2,
      tags         = media.tags
    )
}
