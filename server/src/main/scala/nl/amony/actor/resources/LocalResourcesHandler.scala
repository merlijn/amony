package nl.amony.actor.resources

import akka.NotUsed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.util.FastFuture
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import nl.amony.MediaLibConfig
import nl.amony.actor.JsonSerializable
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.resources.ResourcesProtocol.{CreateFragment, CreateFragments, DeleteFragment, GetThumbnail, GetVideo, GetVideoFragment, IOResponse, ResourceCommand, Upload}
import nl.amony.lib.ffmpeg.FFMpeg
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.util.{Failure, Success}

object LocalResourcesHandler extends Logging {

  case class LocalFileIOResponse(path: Path) extends IOResponse with JsonSerializable {
    override def size(): Long = Files.size(path)
    override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
    override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
      FileIO.fromPath(path, 8192, start).mapMaterializedValue(_ => NotUsed)
  }

  implicit val scheduler = monix.execution.Scheduler.Implicits.global

  def apply(config: MediaLibConfig, scanner: MediaScanner): Behavior[ResourceCommand] = {

    Behaviors.receive { (context, msg) =>

      implicit val mat = SystemMaterializer.get(context.system).materializer

      msg match {

        case GetThumbnail(media, timestamp, quality, sender) =>

          val path = config.resourcePath.resolve(s"${media.id}-${timestamp}_${quality}p.webp")
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case GetVideoFragment(media, timeRange, quality, sender) =>

          val path = config.resourcePath.resolve(s"${media.id}-${timeRange._1}-${timeRange._2}_${quality}p.mp4")
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case GetVideo(media, sender) =>
          val path = config.mediaPath.resolve(media.fileInfo.relativePath)
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case CreateFragment(media, range, overwrite) =>

          val (start, end) = range
          LocalResourcesTasks.createPreview(config, media, start, end, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case CreateFragments(media, overwrite) =>
          LocalResourcesTasks.createFragments(config, media, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case DeleteFragment(media, range) =>
          val (start, end) = range
          LocalResourcesTasks.deleteVideoFragment(config, media, start, end)
          Behaviors.same

        case Upload(fileName, sourceRef, sender) =>

          logger.info(s"Received upload request for: $fileName")

          val path = config.uploadPath.resolve(fileName).toAbsolutePath.normalize()

          Files.createDirectories(config.uploadPath)
          Files.createFile(path)

          sourceRef
            .runWith(FileIO.toPath(path))
            .flatMap { ioResult =>
              ioResult.status match {
                case Success(_) =>
                  scanner.scanMedia(path, None).runToFuture
                case Failure(t) =>
                  logger.info(s"Upload failed")
                  Files.delete(path)
                  FastFuture.failed(t)
              }
            }.foreach { media => sender.tell(media) }

          Behaviors.same
      }
    }
  }
}
