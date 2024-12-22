package nl.amony.lib.magick.model

import io.circe.*

case class ImageGeometry(
  width: Int,
  height: Int,
) derives Decoder

case class ImageMeta(
  geometry: ImageGeometry,
  format: String,
  mimeType: String,
  properties: Map[String, String]
) derives Decoder

case class MagickImageMeta(
  version: String,
  image: ImageMeta
) derives Decoder
