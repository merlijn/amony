package io.amony.http

import io.amony.http.WebModel.{Collection, CreateFragment, Fragment, SearchResult, Video}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

trait JsonCodecs {

  implicit val collectionCodec: Codec[Collection] = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video]           = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult]   = deriveCodec[SearchResult]
  implicit val thumbnailCodec: Codec[Fragment]      = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[CreateFragment]      = deriveCodec[CreateFragment]
}
