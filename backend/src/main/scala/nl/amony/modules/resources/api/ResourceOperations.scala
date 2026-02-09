package nl.amony.modules.resources.api

sealed trait ResourceOperation {
  def contentType: String
  def validate(info: ResourceInfo): Either[String, Unit]
}

case class VideoThumbnail(width: Option[Int] = None, height: Option[Int] = None, quality: Int, timestamp: Long) extends ResourceOperation {
  override def contentType: String = "image/webp"

  override def validate(info: ResourceInfo): Either[String, Unit] = info.basicContentProperties match {
    case Some(video: VideoProperties) =>
      for _ <- Either.cond(timestamp > 0 && timestamp < video.durationInMillis, (), "Timestamp is out of bounds") yield ()
    case other                        => Left("Wrong content type, expected video, got: " + other)
  }
}

object VideoFragment {
  val minHeight         = 120
  val maxHeight         = 4096
  val minLengthInMillis = 1000
  val maxLengthInMillis = 60000
}

case class VideoFragment(width: Option[Int] = None, height: Option[Int] = None, start: Long, end: Long, quality: Int) extends ResourceOperation {

  import VideoFragment.*

  override def contentType: String = "video/mp4"

  override def validate(info: ResourceInfo): Either[String, Unit] = {
    info.basicContentProperties match {
      case Some(video: VideoProperties) =>
        val duration = end - start
        for
          _ <- Either.cond(height.get > minHeight || height.get < maxHeight, (), "Height out of bounds") // TODO Remove Option.get
          _ <- Either.cond(start >= 0, (), "Start time is negative")
          _ <- Either.cond(end > start, (), "End time is before start time")
          _ <- Either.cond(duration > minLengthInMillis, (), "Duration too short")
          _ <- Either.cond(duration < maxLengthInMillis, (), "Duration too long")
        yield ()
      case other                        => Left("Wrong content type, expected video, got: " + other)
    }
  }
}

object ImageThumbnail {
  val minHeight = 64
  val minWidth  = 64
  val maxHeight = 4096
  val maxWidth  = 4096
}

case class ImageThumbnail(width: Option[Int] = None, height: Option[Int] = None, quality: Int) extends ResourceOperation {

  import ImageThumbnail.*

  override def contentType: String = "image/webp"

  override def validate(info: ResourceInfo): Either[String, Unit] =
    for
      _ <- Either.cond(height.getOrElse(Int.MaxValue) > minHeight, (), "Height too small")
      _ <- Either.cond(width.getOrElse(Int.MaxValue) > minWidth, (), "Width too small")
      _ <- Either.cond(height.getOrElse(0) < maxHeight, (), "Height too large")
      _ <- Either.cond(width.getOrElse(0) < maxWidth, (), "Width too large")
    yield ()
}
