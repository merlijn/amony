package nl.amony.service.resources

sealed trait ResourceMeta {
  def contentType: String
}

case class VideoMeta(
  contentType: String,
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Long,
  metaData: Map[String, String],
) extends ResourceMeta

case class ImageMeta(
  contentType: String,
  width: Int,
  height: Int,
  metaData: Map[String, String]
) extends ResourceMeta

case class Other(contentType: String) extends ResourceMeta
