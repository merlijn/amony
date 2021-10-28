package nl.amony.http

object WebModel {

  case class VideoMeta(
    title: Option[String],
    comment: Option[String],
    tags: List[String]
  )

  case class FragmentRange(
    from: Long,
    to: Long
  )

  case class Fragment(
    media_id: String,
    index: Int,
    timestamp_start: Long,
    timestamp_end: Long,
    uri: String,
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
    resolution_x: Int,
    resolution_y: Int
  )

  case class SearchResult(
    offset: Int,
    total: Int,
    videos: Seq[Video]
  )

  case class Tag(
    id: String,
    title: String
  )
}
