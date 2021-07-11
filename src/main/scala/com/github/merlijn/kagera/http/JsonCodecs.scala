package com.github.merlijn.kagera.http

import com.github.merlijn.kagera.actor.MediaLibActor
import com.github.merlijn.kagera.http.Model.{Collection, SearchResult, Thumbnail, Video}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

trait JsonCodecs {

  implicit val collectionCodec: Codec[Collection] = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video]           = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult]   = deriveCodec[SearchResult]
  implicit val thumbnailCode: Codec[Thumbnail]    = deriveCodec[Thumbnail]
}

object WebConversions {

  implicit class MediaOp(media: MediaLibActor.Media) {

    def toWebModel(): Video = Video(
      media.id,
      media.uri,
      media.title,
      media.duration,
      Thumbnail(media.thumbnail.timestamp, media.thumbnail.uri),
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
