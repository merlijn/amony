package io.amony.lib

import akka.util.Timeout
import io.amony.MediaLibConfig
import io.amony.actor.MediaLibActor.{Fragment, Media}
import io.amony.http.JsonCodecs
import io.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.util.Success

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
        val oldHash = config.hashingAlgorithm.generateHash(videoWithoutFastStart)
        val newHash = config.hashingAlgorithm.generateHash(out)

        logger.info(s"$oldHash -> $newHash: ${config.libraryPath.relativize(out).toString}")

        api.query.getById(oldHash).onComplete {
          case Success(Some(v)) =>
            val m = v.copy(id = newHash, hash = newHash, uri = config.libraryPath.relativize(out).toString)

            api.modify.upsertMedia(m).foreach { _ =>
              api.admin.regeneratePreviewFor(m)
              api.modify.deleteMedia(oldHash)
              videoWithoutFastStart.deleteIfExists()
            }
          case other =>
            logger.warn(s"Unexpected result: $other")
        }
      }
  }

  def scanVideo(hash: String, baseDir: Path, videoPath: Path, indexDir: Path): Media = {

    val info           = FFMpeg.ffprobe(videoPath)
    val fragmentLength = 3000

    if (!info.fastStart)
      logger.warn(s"Video is not optimized for streaming: ${videoPath}")

    val attributes = Files.readAttributes(videoPath, classOf[BasicFileAttributes])

    val timeStamp = info.duration / 3
    createVideoFragment(videoPath, indexDir, hash, timeStamp, timeStamp + fragmentLength)

    Media(
      id                 = hash,
      uri                = baseDir.relativize(videoPath).toString,
      addedOnTimestamp   = videoPath.creationTimeMillis(),
      hash               = hash,
      title              = None,
      duration           = info.duration,
      fps                = info.fps,
      thumbnailTimestamp = timeStamp,
      fragments          = List(Fragment(timeStamp, timeStamp + fragmentLength, None, List.empty)),
      tags               = List.empty,
      resolution         = info.resolution
    )
  }

  def scanVideosInDirectory(
      config: MediaLibConfig,
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): (Observable[Media], Observable[Media]) = {

    val files = FileUtil.walkDir(config.libraryPath)

    val filesTruncated = config.max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    // first calculate the hashes
    logger.info("Scanning directory for files & calculating hashes...")

    val filesWithHashes: List[(Path, String)] = Observable
      .from(filesTruncated)
      .filter { file => filterFileName(file.getFileName.toString) }
      .mapParallelUnordered(config.scanParallelFactor) { path =>
        Task {
          val hash = if (config.verifyHashes) {
            config.hashingAlgorithm.generateHash(path)
          } else {
            persistedMedia.find(_.uri == config.libraryPath.relativize(path).toString) match {
              case None    => config.hashingAlgorithm.generateHash(path)
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

    // moved and new
    val newAndMoved = Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        remaining.exists(m => m.hash == hash && m.uri == config.libraryPath.relativize(path).toString)
      }
      .mapParallelUnordered[Media](config.scanParallelFactor) { case (videoFile, hash) =>
        Task {
          val relativePath = config.libraryPath.relativize(videoFile).toString

          remaining.find(_.hash == hash) match {
            case Some(old) =>
              logger.info(s"Detected renamed file: '${old.uri}' -> '${relativePath}'")
              old.copy(uri = relativePath)

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")
              scanVideo(hash, config.libraryPath, videoFile, config.indexPath)
          }
        }
      }

    (Observable.from(removed), newAndMoved)
  }

  def deleteVideoFragment(indexPath: Path, id: String, from: Long, to: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$from.webp").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$from-$to.mp4").deleteIfExists()
  }

  def createVideoFragment(videoPath: Path, indexPath: Path, id: String, from: Long, to: Long): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"

    Files.createDirectories(indexPath.resolve("thumbnails"))

    FFMpeg.writeThumbnail(
      inputFile  = videoPath.absoluteFileName(),
      timestamp  = from,
      outputFile = Some(s"${thumbnailPath}/${id}-$from.webp")
    )

    FFMpeg.writeMp4(
      inputFile  = videoPath.absoluteFileName(),
      from       = from,
      to         = to,
      outputFile = Some(s"${thumbnailPath}/${id}-$from-$to.mp4")
    )
  }
}
