package io.amony.http

import io.amony.actor.MediaLibActor
import io.amony.http.WebModel.{Collection, Preview, SearchResult, Video}

object WebModel {

  case class Preview(
      timestamp_start: Long,
      timestamp_end: Long,
      uri: String
  )

  case class Video(
      id: String,
      uri: String,
      title: String,
      duration: Long,
      fps: Double,
      thumbnail_uri: String,
      previews: List[Preview],
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
        previews = media.previews.map { p =>
          Preview(
            timestamp_start = p.timestampStart,
            timestamp_end   = p.timestampEnd,
            uri             = s"/files/thumbnails/${media.id}-${p.timestampStart}.mp4"
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
