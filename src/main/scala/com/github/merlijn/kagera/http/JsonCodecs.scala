package com.github.merlijn.kagera.http

import com.github.merlijn.kagera.http.Model.{Collection, SearchResult, Video}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

trait JsonCodecs {

  implicit val collectionCodec: Codec[Collection] = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video]           = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult]   = deriveCodec[SearchResult]
}
