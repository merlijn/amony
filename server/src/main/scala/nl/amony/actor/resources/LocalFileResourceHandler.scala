package nl.amony.actor.resources

import akka.NotUsed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import nl.amony.MediaLibConfig
import nl.amony.actor.JsonSerializable
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.resources.ResourcesProtocol.{GetThumbnail, GetVideo, GetVideoFragment, IOResponse, ResourceCommand}
import nl.amony.lib.ffmpeg.FFMpeg

import java.nio.file.{Files, Path}

object LocalFileResourceHandler {

  case class LocalFileIOResponse(path: Path) extends IOResponse with JsonSerializable {
    override def size(): Long = Files.size(path)
    override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
    override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
      FileIO.fromPath(path, 8192, start).mapMaterializedValue(_ => NotUsed)
  }

  def apply(config: MediaLibConfig): Behavior[ResourceCommand] = {

    def thumbnailPath(mediaId: String, timestamp: Long, quality: Int): Path =
      config.resourcePath.resolve(s"${mediaId}-${timestamp}_${quality}p.webp")

    def createThumbnail(media: Media, timestamp: Long, quality: Int) =
      FFMpeg.writeThumbnail(
        inputFile = config.mediaPath.resolve(media.fileInfo.relativePath),
        timestamp = timestamp,
        outputFile = Some(thumbnailPath(media.id, timestamp, quality)),
        scaleHeight = Some(quality)
      )

    def fragmentPath(mediaId: String, range: (Long, Long), quality: Int): Path =
      config.resourcePath.resolve(s"${mediaId}-${range._1}-${range._2}_${quality}p.mp4")

    Behaviors.receiveMessage {
      case GetThumbnail(media, timestamp, quality, sender) =>

        val path = thumbnailPath(media.id, timestamp, quality)
        sender.tell(LocalFileIOResponse(path))
        Behaviors.same

      case GetVideoFragment(media, timeRange, quality, sender) =>

        val path = fragmentPath(media.id, timeRange, quality)
        sender.tell(LocalFileIOResponse(path))
        Behaviors.same

      case GetVideo(media, sender) =>
        val path = config.mediaPath.resolve(media.fileInfo.relativePath)
        sender.tell(LocalFileIOResponse(path))

        Behaviors.same
    }
  }
}
