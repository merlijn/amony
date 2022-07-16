package nl.amony.service.resources.local

import akka.NotUsed
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.util.FastFuture
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.{ByteString, Timeout}
import nl.amony.lib.akka.GraphShapes
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaConfig.{DeleteFile, LocalResourcesConfig, MoveToTrash}
import nl.amony.service.resources.ResourceProtocol._
import nl.amony.service.resources.local.LocalResourcesStore.{GetByHash, LocalFile, LocalResourceCommand}
import nl.amony.service.resources.local.tasks.{CreatePreviews, ScanMedia}
import scribe.Logging

import java.awt.Desktop
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object LocalResourcesHandler extends Logging {

  case class LocalFileIOResponse(path: Path) extends IOResponse {
    override def contentType(): String = ContentTypeResolver.Default.apply(path.getFileName.toString).toString()
    override def size(): Long = Files.size(path)
    override def getContent(): Source[ByteString, NotUsed] = getContentRange(0, size)
    override def getContentRange(start: Long, end: Long): Source[ByteString, NotUsed] =
      FileIO.fromPath(path, 8192, start).mapMaterializedValue(_ => NotUsed)
  }

  def ioResponse(path: Path): Option[IOResponse] = {
    if (path.exists())
      Some(LocalFileIOResponse(path))
    else
      None
  }

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def apply(config: LocalResourcesConfig, store: ActorRef[LocalResourceCommand]): Behavior[ResourceCommand] = {

    Behaviors.receive { (context, msg) =>

      implicit val mat = SystemMaterializer.get(context.system).materializer
      implicit val scheduler = context.system.scheduler
      implicit val askTimeout = Timeout(5.seconds)

      msg match {

        case DeleteResource(media, sender) =>

          val path = media.resolvePath(config.mediaPath)
          logger.info(s"Deleting file: ${path}")

          if (Files.exists(path)) {
            config.deleteMedia match {
              case DeleteFile =>
                Files.delete(path)
              case MoveToTrash =>
                Desktop.getDesktop().moveToTrash(path.toFile())
            }
          };

          sender.tell(true)

          Behaviors.same

        case GetThumbnail(resourceHash, timestamp, quality, sender) =>
          val path = config.resourcePath.resolve(s"${resourceHash}-${timestamp}_${quality}p.webp")
          sender.tell(ioResponse(path))
          Behaviors.same

        case GetVideoFragment(resourceHash, range, quality, sender) =>
          val path = config.resourcePath.resolve(s"${resourceHash}-${range._1}-${range._2}_${quality}p.mp4")
          sender.tell(ioResponse(path))
          Behaviors.same

        case GetResource(resourceHash, sender) =>

          store
            .ask[Option[LocalFile]](ref => GetByHash(resourceHash, ref))
            .foreach { response =>
              sender.tell(response.flatMap(f => ioResponse(config.mediaPath.resolve(f.relativePath))))
            }

          Behaviors.same

        case GetPreviewSpriteImage(mediaId, sender) =>
          val path = config.resourcePath.resolve(s"$mediaId-timeline.webp")
          sender.tell(ioResponse(path))
          Behaviors.same

        case GetPreviewSpriteVtt(mediaId, sender) =>
          val path = config.resourcePath.resolve(s"$mediaId-timeline.vtt")

          val content =
            if (Files.exists(path))
              scala.io.Source.fromFile(path.toFile).mkString
            else
              "WEBVTT"

          sender.tell(Some(content))
          Behaviors.same

        case CreateFragment(media, range, overwrite, sender) =>
          logger.info(s"Creating fragment: ${media.id}-$range")
          CreatePreviews.createVideoPreview(config, media, range, overwrite).executeAsync.runAsync { result =>
            sender.tell(result.isRight)
          }
          Behaviors.same

        case CreateFragments(media, overwrite) =>

          CreatePreviews.createVideoPreviews(config, media, overwrite).executeAsync.runAsyncAndForget
//          LocalResourcesTasks.createPreviewSprite(config, media, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case DeleteFragment(media, range) =>
          val (start, end) = range
          logger.info(s"Deleting fragment: ${media.id}-$range")

          config.resourcePath.resolve(s"${media.id}-$start-${end}_${media.height}p.mp4").deleteIfExists()

          config.transcode.foreach { transcode =>
            config.resourcePath.resolve(s"${media.id}-${start}_${transcode.scaleHeight}p.webp").deleteIfExists()
            config.resourcePath.resolve(s"${media.id}-$start-${end}_${transcode.scaleHeight}p.mp4").deleteIfExists()
          }

          Behaviors.same

        case Upload(fileName, sourceRef, sender) =>
          logger.info(s"Processing upload request: $fileName")

          val path = config.uploadPath.resolve(fileName)

          Files.createDirectories(config.uploadPath)
          Files.createFile(path)

          val hashSink = {
            import java.security.MessageDigest
            Sink.fold[MessageDigest, ByteString](MessageDigest.getInstance(config.hashingAlgorithm.algorithm)) {
              case (digest, bytes) => digest.update(bytes.asByteBuffer); digest
            }
          }

          val toPathSink = FileIO.toPath(path)

          val (hashF, ioF) = GraphShapes.broadcast(sourceRef.source, hashSink, toPathSink).run()

          val futureResult = for (
            hash     <- hashF;
            ioResult <- ioF
          ) yield (config.hashingAlgorithm.encodeHash(hash.digest()), ioResult)

          futureResult
            .flatMap { case (hash, ioResult) =>
              ioResult.status match {
                case Success(_) =>
                  ScanMedia.scanMedia(config.mediaPath, config.relativeUploadPath.resolve(fileName), hash).runToFuture
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
