package nl.amony.actor.resources

import akka.NotUsed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.util.FastFuture
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.actor.resources.ResourcesProtocol._
import scribe.Logging

import java.nio.file.Files
import java.nio.file.Path
import scala.util.Failure
import scala.util.Success

object LocalResourcesHandler extends Logging {

  case class LocalFileIOResponse(path: Path) extends IOResponse {
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

        case GetThumbnail(mediaId, timestamp, quality, sender) =>
          val path = config.resourcePath.resolve(s"${mediaId}-${timestamp}_${quality}p.webp")
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case GetVideoFragment(mediaId, range, quality, sender) =>
          val path = config.resourcePath.resolve(s"${mediaId}-${range._1}-${range._2}_${quality}p.mp4")
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case GetVideo(media, sender) =>
          val path = config.mediaPath.resolve(media.fileInfo.relativePath)
          sender.tell(LocalFileIOResponse(path))
          Behaviors.same

        case CreateFragment(media, range, overwrite, sender) =>
          LocalResourcesTasks.createPreview(config, media, range, overwrite).executeAsync.runAsync { result =>
            sender.tell(result.isRight)
          }
          Behaviors.same

        case CreateFragments(media, overwrite) =>
          LocalResourcesTasks.createFragments(config, media, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case DeleteFragment(media, range) =>
          val (start, end) = range
          LocalResourcesTasks.deleteVideoFragment(config, media, start, end)
          Behaviors.same

        case Upload(fileName, sourceRef, sender) =>
          logger.info(s"Processing upload request: $fileName")

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
                  logger.warn(s"Upload failed", t)
                  Files.delete(path)
                  FastFuture.failed(t)
              }
            }
            .foreach { media => sender.tell(media) }

          Behaviors.same
      }
    }
  }
}
