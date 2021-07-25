package io.amony.lib

import akka.actor.typed.ActorRef
import better.files.File
import io.amony.actor.MediaLibActor.{UpsertMedia, Command, Media, Thumbnail}
import io.amony.http.JsonCodecs
import io.amony.lib.FFMpeg.Probe
import io.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.{Files, Path}

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    max: Option[Int]
)

object MediaLibScanner extends Logging with JsonCodecs {

  def scanVideo(persistedMedia: List[Media], baseDir: Path, videoPath: Path, indexDir: Path): Media = {

    val info      = FFMpeg.ffprobe(videoPath)
    val hash      = FileUtil.fakeHash(videoPath)

    persistedMedia.find(_.hash == hash) match {
      case Some(old) =>
        val newUri = baseDir.relativize(Path.of(info.fileName)).toString
        logger.info(s"Detected renamed file: '${old.uri}' -> ${newUri}")
        old.copy(uri = newUri)
      case None      =>
        logger.info(s"Scanning new file: ${videoPath.toAbsolutePath}")
        val timeStamp = info.duration / 3
        generateThumbnail(videoPath, indexDir, hash, timeStamp)
        val video = asVideo(baseDir, hash, info, timeStamp)
        video
    }
  }

  def scanVideosInPath(
      scanPath: Path,
      indexPath: Path,
      parallelFactor: Int,
      max: Option[Int],
      persistedMedia: List[Media],
      extensions: List[String] = List("mp4", "webm")
  )(implicit s: Scheduler): Observable[Media] = {

    val files = FileUtil.walkDir(scanPath)

    val truncatedMaybe = max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    Observable
      .from(truncatedMaybe)
      .filter { vid =>
        val fileName = vid.getFileName.toString

        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .filter { vid =>
        val relativePath   = scanPath.relativize(vid).toString
        !persistedMedia.exists(_.uri == relativePath)
      }
      .mapParallelUnordered(parallelFactor) { p =>
        Task {
          scanVideo(persistedMedia, scanPath, p, indexPath)
        }
      }
  }

  def deleteThumbnailAtTimestamp(indexPath: Path, id: String, timestamp: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$timestamp.jpeg").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$timestamp.webp").deleteIfExists()
  }

  def generateThumbnail(videoPath: Path, indexPath: Path, id: String, timestamp: Long): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"

    Files.createDirectories(indexPath.resolve("thumbnails"))

    FFMpeg.writeThumbnail(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp-thumbnail.webp")
    )

    FFMpeg.createMp4(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp-preview.mp4")
    )
  }

  protected def asVideo(baseDir: Path, hash: String, info: Probe, thumbnailTimestamp: Long): Media = {

    val relativePath = baseDir.relativize(Path.of(info.fileName)).toString

    Media(
      id         = hash,
      uri        = relativePath,
      hash       = hash,
      title      = None,
      duration   = info.duration,
      fps        = info.fps,
      thumbnail  = Thumbnail(thumbnailTimestamp),
      tags       = List.empty,
      resolution = info.resolution
    )
  }

  def scan(config: MediaLibConfig, last: List[Media], actorRef: ActorRef[Command]): Unit = {

    implicit val s = Scheduler.global
    val libraryDir = File(config.libraryPath)

    // create the index directory if it does not exist
    val indexDir = File(config.indexPath)
    if (!indexDir.exists)
      indexDir.createDirectory()

    val obs = scanVideosInPath(libraryDir.path, config.indexPath, config.scanParallelFactor, config.max, last)

    val c = Consumer.foreachTask[Media](m =>
      Task {
        actorRef.tell(UpsertMedia(m))
      }
    )

    obs.consumeWith(c).runSyncUnsafe()
  }
}
