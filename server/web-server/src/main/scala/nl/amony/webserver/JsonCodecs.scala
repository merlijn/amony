package nl.amony.webserver

import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import nl.amony.search.SearchProtocol
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.actor.MediaLibProtocol
import nl.amony.webserver.WebModel._

class JsonCodecs(transcodingSettings: List[TranscodeSettings]) {

  // web model codecs
  implicit val fragmentCodec: Codec[Fragment]            = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[FragmentRange] = deriveCodec[FragmentRange]
  implicit val searchResultCodec: Codec[SearchResult]    = deriveCodec[SearchResult]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val videoMetaCodec: Codec[VideoMeta]          = deriveCodec[VideoMeta]
  implicit val tagCodec: Codec[Playlist]                 = deriveCodec[Playlist]

  // contra map encoders for internal classes
  implicit val mediaEncoder: Encoder[MediaLibProtocol.Media] =
    deriveEncoder[Video].contramapObject[MediaLibProtocol.Media](toWebModel)

  implicit val searchResultEncoder: Encoder[SearchProtocol.SearchResult] =
    deriveEncoder[SearchResult].contramapObject[SearchProtocol.SearchResult](result =>
      SearchResult(result.offset, result.total, result.items.map(m => toWebModel(m)))
    )

  def toWebModel(mediaId: String, f: MediaLibProtocol.Fragment): Fragment = {

    val resolutions = transcodingSettings.map(_.scaleHeight).sorted
    val urls =
      resolutions.map(height => s"/resources/media/${mediaId}~${f.fromTimestamp}-${f.toTimestamp}_${height}p.mp4")

    Fragment(
      mediaId,
      0,
      FragmentRange(f.fromTimestamp, f.toTimestamp),
      urls,
      f.comment,
      f.tags
    )
  }

  def toWebModel(media: MediaLibProtocol.Media): Video = {

    val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

    Video(
      id        = media.id,
      video_url = s"/resources/media/${media.id}_${media.height}p.${media.fileInfo.extension}",
      meta = VideoMeta(
        title   = media.title.orElse(Some(media.fileName())),
        comment = media.comment,
        tags    = media.tags.toList
      ),
      duration               = media.videoInfo.duration,
      addedOn                = media.fileInfo.creationTime,
      fps                    = media.videoInfo.fps,
      size                   = media.fileInfo.size,
      thumbnail_url          = s"/resources/media/${media.id}_${resolutions.min}p.webp",
      preview_thumbnails_url = Some(s"/resources/media/${media.id}-timeline.vtt"),
      fragments = {
        media.fragments.zipWithIndex.map { case (f, index) =>
          val urls = resolutions.map(height =>
            s"/resources/media/${media.id}~${f.fromTimestamp}-${f.toTimestamp}_${height}p.mp4"
          )

          Fragment(
            media_id = media.id,
            index    = index,
            range    = FragmentRange(f.fromTimestamp, f.toTimestamp),
            urls     = urls,
            comment  = f.comment,
            tags     = f.tags
          )
        }
      },
      width  = media.width,
      height = media.height
    )
  }
}
