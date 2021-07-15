package io.amony.lib

import akka.actor.typed.ActorRef
import better.files.File
import io.amony.actor.MediaLibActor
import io.amony.actor.MediaLibActor.{AddMedia, Media, Thumbnail}
import io.amony.http.JsonCodecs
import io.amony.lib.FFMpeg.Probe
import io.amony.lib.FileUtil.PathOps
import io.amony.actor.MediaLibActor.{Command, Media}
import io.amony.http.JsonCodecs
import io.amony.lib.FFMpeg.Probe
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.Path

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    max: Option[Int]
)

object MediaLibScanner extends Logging with JsonCodecs {

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Media = {
    logger.info(s"Scanning file: ${videoPath.toAbsolutePath}")

    val info      = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3
    val hash      = FileUtil.fakeHash(videoPath)

    generateThumbnail(videoPath, indexDir, hash, timeStamp)

    val video = asVideo(baseDir, hash, info, timeStamp)

    video
  }

  def scanVideosInPath(
      scanPath: Path,
      indexPath: Path,
      parallelFactor: Int,
      max: Option[Int],
      last: List[Media],
      extensions: List[String] = List("mp4", "webm")
  )(implicit s: Scheduler): Observable[Media] = {

    val files = FileUtil.walkDir(scanPath)

    logger.info(s"max: $max")

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
        val alreadyIndexed = last.exists(_.uri == relativePath)

        if (alreadyIndexed)
          logger.info(s"Skipping already indexed: ${relativePath}")

        !alreadyIndexed
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanVideo(scanPath, p, indexPath) } }
  }

  def deleteThumbnailAtTimestamp(indexPath: Path, id: String, timestamp: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$timestamp.jpeg").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$timestamp.webp").deleteIfExists()
  }

  def generateThumbnail(videoPath: Path, indexPath: Path, id: String, timestamp: Long): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"
    val thumbnailDir  = File(thumbnailPath)

    if (!thumbnailDir.exists)
      thumbnailDir.createDirectory()

    FFMpeg.writeThumbnail(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp.jpeg")
    )

    FFMpeg.createWebP(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp.webp")
    )
  }

  protected def asVideo(baseDir: Path, hash: String, info: Probe, thumbnailTimestamp: Long): Media = {

    val relativePath = baseDir.relativize(Path.of(info.fileName)).toString

    val slashIdx = relativePath.lastIndexOf('/')
    val dotIdx   = relativePath.lastIndexOf('.')

    val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
    val endIdx   = if (dotIdx >= 0) dotIdx else relativePath.length

    val title = relativePath.substring(startIdx, endIdx)

    Media(
      id         = hash,
      uri        = relativePath,
      hash       = hash,
      title      = title,
      duration   = info.duration,
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

    val c = Consumer.foreachTask[Media](m => Task { actorRef.tell(AddMedia(m)) })

    obs.consumeWith(c).runSyncUnsafe()
  }
}
