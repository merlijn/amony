package nl.amony.http

import nl.amony.actor.MediaLibProtocol
import nl.amony.http.WebModel.{Tag, Fragment, FragmentRange, SearchResult, Video}
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
  implicit val mediaEncoder: Encoder[MediaLibProtocol.Media] =
    deriveEncoder[Video].contramapObject[MediaLibProtocol.Media](toWebModel)

  implicit val tagEncoder: Encoder[MediaLibProtocol.Directory] =
    deriveEncoder[Tag].contramapObject[MediaLibProtocol.Directory](c => Tag(c.id, c.path))

  implicit val searchResultEncoder: Encoder[MediaLibProtocol.SearchResult] =
    deriveEncoder[SearchResult].contramapObject[MediaLibProtocol.SearchResult](result =>
      SearchResult(result.offset, result.total, result.items.map(m => toWebModel(m)))
    )

  def toWebModel(media: MediaLibProtocol.Media): Video =
    Video(
      id            = media.id,
      uri           = s"/files/videos/${media.fileInfo.relativePath}",
      title         = media.title.getOrElse(media.fileName()),
      duration      = media.videoInfo.duration,
      addedOn       = media.fileInfo.creationTime,
      fps           = media.videoInfo.fps,
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
      resolution_x = media.videoInfo.resolution._1,
      resolution_y = media.videoInfo.resolution._2,
      tags         = media.tags
    )
}
