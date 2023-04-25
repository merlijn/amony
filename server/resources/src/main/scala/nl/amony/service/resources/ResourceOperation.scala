package nl.amony.service.resources

sealed trait ResourceOperation

case class VideoFragment(
  start: Long,
  end: Long,
  quality: Int
) extends ResourceOperation

case class VideoTranscode(
  scaleHeight: Int
) extends ResourceOperation

case class VideoThumbnail(
  timestamp: Long,
  quality: Int,
) extends ResourceOperation

case class ImageThumbnail(
  scaleHeight: Int
) extends ResourceOperation
