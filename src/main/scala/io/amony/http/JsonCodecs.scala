package io.amony.http

import io.amony.actor.MediaLibActor
import io.amony.http.Model.{Collection, SearchResult, Preview, Video}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

trait JsonCodecs {

  implicit val collectionCodec: Codec[Collection] = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video]           = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult]   = deriveCodec[SearchResult]
  implicit val thumbnailCode: Codec[Preview]      = deriveCodec[Preview]
}

object WebConversions {

  implicit class MediaOp(media: MediaLibActor.Media) {

    def toWebModel(): Video =
      Video(
        id = media.id,
        uri = s"/files/videos/${media.uri}",
        title = media.title.getOrElse(media.fileName()),
        duration = media.duration,
        fps = media.fps,
        thumbnail_uri = s"/files/thumbnails/${media.id}-${media.thumbnail.timestamp}-thumbnail.webp",
        previews = List(
          Preview(
            timestamp_start = media.thumbnail.timestamp,
            timestamp_end = media.thumbnail.timestamp + 3000,
            uri =  s"/files/thumbnails/${media.id}-${media.thumbnail.timestamp}-preview.mp4",
          )),
        resolution_x = media.resolution._1,
        resolution_y = media.resolution._2,
        tags = media.tags
      )
  }

  implicit class CollectionOp(c: MediaLibActor.Collection) {
    def toWebModel(): Collection = Collection(c.id, c.title)
  }

  implicit class SearchResultOp(result: MediaLibActor.SearchResult) {
    def toWebModel(): SearchResult = SearchResult(result.offset, result.total, result.items.map(_.toWebModel()))
  }
}
