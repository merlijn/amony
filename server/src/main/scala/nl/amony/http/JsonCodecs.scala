package nl.amony.http

import nl.amony.actor.MediaLibProtocol
import nl.amony.http.WebModel.Fragment
import nl.amony.http.WebModel.FragmentRange
import nl.amony.http.WebModel.SearchResult
import nl.amony.http.WebModel.Playlist
import nl.amony.http.WebModel.Video
import nl.amony.http.WebModel.VideoMeta
import io.circe.Codec
import io.circe.Encoder
import io.circe.generic.semiauto.deriveCodec
import io.circe.generic.semiauto.deriveEncoder
import nl.amony.TranscodeSettings
import nl.amony.actor.index.{LocalIndex, QueryProtocol}

trait JsonCodecs {

  def transcodingSettings: List[TranscodeSettings]

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

  implicit val tagEncoder: Encoder[QueryProtocol.Playlist] =
    deriveEncoder[Playlist].contramapObject[QueryProtocol.Playlist](c => Playlist(c.id, c.title))

  implicit val searchResultEncoder: Encoder[QueryProtocol.SearchResult] =
    deriveEncoder[SearchResult].contramapObject[QueryProtocol.SearchResult](result =>
      SearchResult(result.offset, result.total, result.items.map(m => toWebModel(m)))
    )

  def toWebModel(f: MediaLibProtocol.Fragment): Fragment = {
    Fragment(
      "",
      0,
      FragmentRange(f.fromTimestamp, f.toTimestamp),
      List.empty,
      f.comment,
      f.tags
    )
  }

  def toWebModel(media: MediaLibProtocol.Media): Video = {
    
    val resolutions = (media.height :: transcodingSettings.map(_.scaleHeight)).sorted

    Video(
      id        = media.id,
      video_url = s"/files/resources/${media.id}_${media.videoInfo.resolution._2}p.${media.fileInfo.extension}",
      meta = VideoMeta(
        title   = media.title.orElse(Some(media.fileName())),
        comment = media.comment,
        tags    = media.tags.toList
      ),
      duration               = media.videoInfo.duration,
      addedOn                = media.fileInfo.creationTime,
      fps                    = media.videoInfo.fps,
      size                   = media.fileInfo.size,
      thumbnail_url          = s"/files/resources/${media.id}_${resolutions.min}p.webp",
      preview_thumbnails_url = Some(s"/files/resources/${media.id}-timeline.vtt"),
      fragments = {
        media.fragments.zipWithIndex.map { case (f, index) =>
          val urls = resolutions.map(height => s"/files/resources/${media.id}~${f.fromTimestamp}-${f.toTimestamp}_${height}p.mp4")

          Fragment(
            media_id        = media.id,
            index           = index,
            range           = FragmentRange(f.fromTimestamp, f.toTimestamp),
            urls            = urls,
            comment         = f.comment,
            tags            = f.tags
          )
        }
      },
      width  = media.videoInfo.resolution._1,
      height = media.videoInfo.resolution._2
    )
  }
}
