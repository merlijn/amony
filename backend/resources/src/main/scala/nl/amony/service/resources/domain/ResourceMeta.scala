package nl.amony.service.resources.domain

case class ResourceMetaSource(
 toolName: String,
 toolData: String
)

sealed trait ResourceMeta

case class VideoMeta(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String] = None,
  metaData: Map[String, String] = Map.empty
) extends ResourceMeta

case class ImageMeta(
  width: Int,
  height: Int,
  metaData: Map[String, String] = Map.empty
) extends ResourceMeta