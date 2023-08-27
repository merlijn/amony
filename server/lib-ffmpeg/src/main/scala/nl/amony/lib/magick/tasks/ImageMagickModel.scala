package nl.amony.lib.magick.tasks

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

object ImageMagickModel {

  case class ImageGeometry(
    width: Int,
    height: Int,
  )

  case class ImageMeta(
    geometry: ImageGeometry,
    format: String,
    mimeType: String,
    properties: Map[String, String]
  )

  case class MagickImageMeta(
    version: String,
    image: ImageMeta
  )

  implicit val imageMetaDecoder: Decoder[ImageMeta] = deriveDecoder[ImageMeta]
  implicit val geometryDecoder: Decoder[ImageGeometry] = deriveDecoder[ImageGeometry]
  implicit val magickMetaDecoder: Decoder[MagickImageMeta] = deriveDecoder[MagickImageMeta]
}
