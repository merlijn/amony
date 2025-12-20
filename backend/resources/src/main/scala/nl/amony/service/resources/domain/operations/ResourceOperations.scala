package nl.amony.service.resources.domain.operations

sealed trait ResourceOperation

object ResourceOperation:
  case object Empty extends ResourceOperation

case class VideoThumbnail(
  width: Option[Int] = None,
  height: Option[Int] = None,
  quality: Int,
  timestamp: Long
) extends ResourceOperation

case class VideoFragment(
  width: Option[Int] = None,
  height: Option[Int] = None,
  start: Long,
  end: Long,
  quality: Int
) extends ResourceOperation

case class ImageThumbnail(
  width: Option[Int] = None,
  height: Option[Int] = None,
  quality: Int
) extends ResourceOperation
