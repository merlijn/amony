package nl.amony.http

import nl.amony.actor.MediaLibProtocol
import nl.amony.http.WebModel.{Fragment, FragmentRange, SearchResult, Tag, Video, VideoMeta}
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}

trait JsonCodecs {

  // web model codecs
  implicit val thumbnailCodec: Codec[Fragment]           = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[FragmentRange] = deriveCodec[FragmentRange]
  implicit val searchResultCodec: Codec[SearchResult]    = deriveCodec[SearchResult]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val videoMetaCodec: Codec[VideoMeta]          = deriveCodec[VideoMeta]
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
      id  = media.id,
      uri = s"/files/videos/${media.fileInfo.relativePath}",
      meta =
        VideoMeta(title = media.title.orElse(Some(media.fileName())), comment = media.comment, tags = media.tags.toList),
      duration      = media.videoInfo.duration,
      addedOn       = media.fileInfo.creationTime,
      fps           = media.videoInfo.fps,
      thumbnail_uri = s"/files/thumbnails/${media.id}.webp",
      fragments = media.fragments.zipWithIndex.map { case (f, index) =>
        Fragment(
          mediaId         = media.id,
          index           = index,
          timestamp_start = f.fromTimestamp,
          timestamp_end   = f.toTimestamp,
          uri             = s"/files/thumbnails/${media.id}-${f.fromTimestamp}-${f.toTimestamp}.mp4",
          comment         = f.comment,
          tags            = f.tags
        )
      },
      resolution_x = media.videoInfo.resolution._1,
      resolution_y = media.videoInfo.resolution._2
    )
}
