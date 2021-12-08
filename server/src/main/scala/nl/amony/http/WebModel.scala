package nl.amony.http

object WebModel {

  case class FragmentRange(
      from: Long,
      to: Long
  )

  case class Fragment(
      media_id: String,
      index: Int,
      range: FragmentRange,
      urls: List[String],
      comment: Option[String],
      tags: List[String]
  )

  case class VideoMeta(
      title: Option[String],
      comment: Option[String],
      tags: List[String]
  )

  case class Video(
      id: String,
      video_url: String,
      meta: VideoMeta,
      duration: Long,
      addedOn: Long,
      fps: Double,
      thumbnail_url: String,
      preview_thumbnails_url: Option[String],
      fragments: List[Fragment],
      width: Int,
      height: Int
  )

  case class SearchResult(
    offset: Int,
    total: Int,
    videos: Seq[Video]
  )

  case class Playlist(
    id: String,
    title: String
  )
}
