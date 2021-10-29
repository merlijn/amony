package nl.amony.http

import nl.amony.actor.{MediaIndex, MediaLibProtocol}
import nl.amony.http.WebModel.{Fragment, FragmentRange, SearchResult, Tag, Video, VideoMeta}
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import nl.amony.TranscodeSettings

trait JsonCodecs {

  def transcodingSettings: List[TranscodeSettings]

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

  implicit val tagEncoder: Encoder[MediaIndex.Directory] =
    deriveEncoder[Tag].contramapObject[MediaIndex.Directory](c => Tag(c.id, c.path))

  implicit val searchResultEncoder: Encoder[MediaIndex.SearchResult] =
    deriveEncoder[SearchResult].contramapObject[MediaIndex.SearchResult](result =>
      SearchResult(result.offset, result.total, result.items.map(m => toWebModel(m)))
    )

  def toWebModel(media: MediaLibProtocol.Media): Video =
    Video(
      id  = media.id,
      video_url = s"/files/resources/${media.id}_${media.videoInfo.resolution._2}p.${media.fileInfo.extension}",
      meta =
        VideoMeta(title = media.title.orElse(Some(media.fileName())), comment = media.comment, tags = media.tags.toList),
      duration      = media.videoInfo.duration,
      addedOn       = media.fileInfo.creationTime,
      fps           = media.videoInfo.fps,
      thumbnail_url = s"/files/resources/${media.id}_320p.webp",
      preview_thumbnails_url = Some(s"/files/resources/${media.id}-timeline.vtt"),
      fragments = {

        val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

        media.fragments.zipWithIndex.map { case (f, index) =>

          val urls = resolutions.map(
            h => s"/files/resources/${media.id}~${f.fromTimestamp}-${f.toTimestamp}_${h}p.mp4"
          )

          Fragment(
            media_id        = media.id,
            index           = index,
            timestamp_start = f.fromTimestamp,
            timestamp_end   = f.toTimestamp,
            urls            = urls,
            comment         = f.comment,
            tags            = f.tags
          )
        }
      },
      width = media.videoInfo.resolution._1,
      height = media.videoInfo.resolution._2
    )
}
