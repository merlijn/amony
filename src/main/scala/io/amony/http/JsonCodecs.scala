package io.amony.http

import io.amony.http.WebModel.{Collection, FragmentRange, Fragment, SearchResult, Video}
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}

trait JsonCodecs {

  implicit val collectionCodec: Codec[Collection]        = deriveCodec[Collection]
  implicit val videoCodec: Codec[Video]                  = deriveCodec[Video]
  implicit val resultCodec: Codec[SearchResult]          = deriveCodec[SearchResult]
  implicit val thumbnailCodec: Codec[Fragment]           = deriveCodec[Fragment]
  implicit val createFragmentCodec: Codec[FragmentRange] = deriveCodec[FragmentRange]
}
