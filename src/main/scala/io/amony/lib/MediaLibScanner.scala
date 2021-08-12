package io.amony.lib

import akka.util.Timeout
import better.files.File
import io.amony.MediaLibConfig
import io.amony.actor.MediaLibActor.{Fragment, Media}
import io.amony.http.JsonCodecs
import io.amony.lib.FFMpeg.Probe
import io.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object MediaLibScanner extends Logging with JsonCodecs {

  def filterFileName(fileName: String): Boolean = {
    fileName.endsWith(".mp4") && !fileName.startsWith(".")
  }

  def convertNonStreamableVideos(config: MediaLibConfig, api: MediaLibApi): Unit = {

    val files = FileUtil.walkDir(config.libraryPath)

    implicit val timeout: Timeout = Timeout(3.seconds)
    implicit val ec               = scala.concurrent.ExecutionContext.global

    files
      .filter { vid =>
        // filter for extension
        filterFileName(vid.getFileName().toString) && !FFMpeg.ffprobe(vid).fastStart
      }
      .foreach { videoWithoutFastStart =>
        logger.info(s"Creating faststart/streamable mp4 for: ${videoWithoutFastStart}")

        val out     = FFMpeg.addFastStart(videoWithoutFastStart)
        val oldHash = FileUtil.fakeHash(File(videoWithoutFastStart))
        val newHash = FileUtil.fakeHash(File(out))

        logger.info(s"$oldHash -> $newHash: ${config.libraryPath.relativize(out).toString}")

        api.read.getById(oldHash).foreach { v =>
          val m = v.copy(id = newHash, hash = newHash, uri = config.libraryPath.relativize(out).toString)

          api.modify.upsertMedia(m).foreach { _ =>
            api.admin.regeneratePreviewFor(m)
            api.modify.deleteMedia(oldHash)
            videoWithoutFastStart.deleteIfExists()
          }
        }
      }
  }

  def scanDirectory(config: MediaLibConfig, last: List[Media], api: MediaLibApi)(implicit timeout: Timeout): Unit = {

    implicit val s = Scheduler.global
    val libraryDir = File(config.libraryPath)

    // create the index directory if it does not exist
    val indexDir = File(config.indexPath)
    if (!indexDir.exists)
      indexDir.createDirectory()

    val obs = scanVideosInDirectory(
      api,
      libraryDir.path,
      config.indexPath,
      config.verifyHashes,
      config.scanParallelFactor,
      config.max,
      last
    )

    val c = Consumer.foreachTask[Media](m =>
      Task {
        api.modify.upsertMedia(m)
      }
    )

    obs.consumeWith(c).runSyncUnsafe()
  }

  def scanVideo(hash: String, baseDir: Path, videoPath: Path, indexDir: Path): Media = {

    val info      = FFMpeg.ffprobe(videoPath)

    if (!info.fastStart)
      logger.warn(s"Video is not optimized for streaming: ${videoPath}")

    val timeStamp = info.duration / 3
    createVideoFragment(videoPath, indexDir, hash, timeStamp, timeStamp + 3000)
    val video = asVideo(baseDir, videoPath, hash, info, timeStamp)
    video
  }

  def scanVideosInDirectory(
      api: MediaLibApi,
      scanPath: Path,
      indexPath: Path,
      verifyHashes: Boolean,
      parallelFactor: Int,
      max: Option[Int],
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): Observable[Media] = {

    val files = FileUtil.walkDir(scanPath)

    val filesTruncated = max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    // first calculate the hashes
    logger.info("Scanning directory for files & calculating hashes...")

    val filesWithHashes: List[(Path, String)] = Observable
      .from(filesTruncated)
      .filter { vid =>
        // filter for extension
        filterFileName(vid.getFileName.toString)
      }
      .mapParallelUnordered(parallelFactor) { path =>
        Task {
          val hash = if (verifyHashes) {
            FileUtil.fakeHash(path)
          } else {
            persistedMedia.find(_.uri == scanPath.relativize(path).toString) match {
              case None    => FileUtil.fakeHash(path)
              case Some(m) => m.hash
            }
          }

          (path, hash)
        }
      }
      .consumeWith(Consumer.toList)
      .runSyncUnsafe()

    val (remaining, removed) =
      persistedMedia.partition(m => filesWithHashes.exists { case (_, hash) => hash == m.hash })

    logger.info(s"Scanning done, found ${filesWithHashes.size} files")

    // removed
    removed.foreach { m =>
      logger.info(s"Detected deleted file: ${m.uri}")
      api.modify.deleteMedia(m.id)
    }

    // moved and new
    Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        remaining.exists(m => m.hash == hash && m.uri == scanPath.relativize(path).toString)
      }
      .mapParallelUnordered[Media](parallelFactor) { case (videoFile, hash) =>
        Task {
          val relativePath = scanPath.relativize(videoFile).toString

          remaining.find(_.hash == hash) match {
            case Some(old) =>
              logger.info(s"Detected renamed file: '${old.uri}' -> '${relativePath}'")
              old.copy(uri = relativePath)

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")

              scanVideo(hash, scanPath, videoFile, indexPath)
          }
        }
      }
  }

  def deleteVideoFragment(indexPath: Path, id: String, from: Long, to: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$from.webp").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$from-$to.mp4").deleteIfExists()
  }

  /** Generates a thumbnail and mp4 for a video fragment
    */
  def createVideoFragment(videoPath: Path, indexPath: Path, id: String, from: Long, to: Long): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"

    Files.createDirectories(indexPath.resolve("thumbnails"))

    FFMpeg.writeThumbnail(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = from,
      outputFile = Some(s"${thumbnailPath}/${id}-$from.webp")
    )

    FFMpeg.createMp4(
      inputFile  = videoPath.absoluteFileName(),
      from       = from,
      to         = to,
      outputFile = Some(s"${thumbnailPath}/${id}-$from-$to.mp4")
    )
  }

  protected def asVideo(baseDir: Path, videoPath: Path, hash: String, info: Probe, thumbnailTimestamp: Long): Media = {

    Media(
      id                 = hash,
      uri                = baseDir.relativize(videoPath).toString,
      hash               = hash,
      title              = None,
      duration           = info.duration,
      fps                = info.fps,
      thumbnailTimestamp = thumbnailTimestamp,
      fragments          = List(Fragment(thumbnailTimestamp, thumbnailTimestamp + 3000, None, List.empty)),
      tags               = List.empty,
      resolution         = info.resolution
    )
  }
}
