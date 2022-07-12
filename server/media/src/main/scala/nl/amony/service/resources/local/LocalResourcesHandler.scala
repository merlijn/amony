package nl.amony.service.resources.local

import akka.NotUsed
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, SystemMaterializer}
import akka.util.ByteString
import nl.amony.lib.akka.GraphShapes
import nl.amony.lib.files.PathOps
import nl.amony.lib.hash.Base16
import nl.amony.service.media.MediaConfig.{DeleteFile, LocalResourcesConfig, MoveToTrash}
import nl.amony.service.resources.ResourceProtocol._
import scribe.Logging

import java.awt.Desktop
import java.nio.file.{Files, Path}
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

  implicit val scheduler = monix.execution.Scheduler.Implicits.global

  def apply(config: LocalResourcesConfig, scanner: LocalMediaScanner): Behavior[ResourceCommand] = {

    Behaviors.receive { (context, msg) =>
      implicit val mat = SystemMaterializer.get(context.system).materializer

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

        case GetThumbnail(mediaId, timestamp, quality, sender) =>
          val path = config.resourcePath.resolve(s"${mediaId}-${timestamp}_${quality}p.webp")
          sender.tell(ioResponse(path))
          Behaviors.same

        case GetVideoFragment(mediaId, range, quality, sender) =>
          val path = config.resourcePath.resolve(s"${mediaId}-${range._1}-${range._2}_${quality}p.mp4")
          sender.tell(ioResponse(path))
          Behaviors.same

        case GetVideo(media, sender) =>
          val path = config.mediaPath.resolve(media.fileInfo.relativePath)
          sender.tell(ioResponse(path))
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
          LocalResourcesTasks.createPreview(config, media, range, overwrite).executeAsync.runAsync { result =>
            sender.tell(result.isRight)
          }
          Behaviors.same

        case CreateFragments(media, overwrite) =>

          LocalResourcesTasks.createFragments(config, media, overwrite).executeAsync.runAsyncAndForget
//          LocalResourcesTasks.createPreviewSprite(config, media, overwrite).executeAsync.runAsyncAndForget
          Behaviors.same

        case DeleteFragment(media, range) =>
          val (start, end) = range
          logger.info(s"Deleting fragment: ${media.id}-$range")
          LocalResourcesTasks.deleteVideoFragment(config, media, start, end)
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
                  scanner.scanMedia(path, Some(hash)).runToFuture
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
