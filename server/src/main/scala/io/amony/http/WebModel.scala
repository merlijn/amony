package io.amony.http

import io.amony.actor.MediaLibActor
import io.amony.http.WebModel.{Collection, Fragment, SearchResult, Video}

object WebModel {

  case class FragmentRange(
      from: Long,
      to: Long
  )

  case class Fragment(
      index: Int,
      timestamp_start: Long,
      timestamp_end: Long,
      uri: String,
      comment: Option[String],
      tags: List[String]
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      fps: Double,
      thumbnail_uri: String,
      fragments: List[Fragment],
      resolution_x: Int,
      resolution_y: Int,
      tags: Seq[String]
  )

  case class SearchResult(
      offset: Int,
      total: Int,
      videos: Seq[Video]
  )

  case class Collection(
      id: String,
      title: String
  )
}

object WebConversions {

  implicit class MediaOp(media: MediaLibActor.Media) {

    def toWebModel(): Video =
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

  implicit class CollectionOp(c: MediaLibActor.Collection) {
    def toWebModel(): Collection = Collection(c.id, c.title)
  }

  implicit class SearchResultOp(result: MediaLibActor.SearchResult) {
    def toWebModel(): SearchResult = SearchResult(result.offset, result.total, result.items.map(_.toWebModel()))
  }
}
