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
        media.id,
        s"/files/videos/${media.uri}",
        media.title,
        media.duration,
        Preview(
          media.thumbnail.timestamp,
          s"/files/thumbnails/${media.id}-${media.thumbnail.timestamp}.jpeg",
          s"/files/thumbnails/${media.id}-${media.thumbnail.timestamp}.webp"
        ),
        s"${media.resolution._1}x${media.resolution._2}",
        media.tags
      )
  }

  implicit class CollectionOp(c: MediaLibActor.Collection) {
    def toWebModel(): Collection = Collection(c.id, c.title)
  }

  implicit class SearchResultOp(result: MediaLibActor.SearchResult) {
    def toWebModel(): SearchResult = SearchResult(result.offset, result.total, result.items.map(_.toWebModel()))
  }
}
