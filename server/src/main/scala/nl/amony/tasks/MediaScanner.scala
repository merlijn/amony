package nl.amony.tasks

import akka.util.Timeout
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import nl.amony.actor.media.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import nl.amony.lib.FileUtil
import nl.amony.lib.FileUtil.PathOps
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.{AmonyConfig, MediaLibConfig}
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

class MediaScanner(appConfig: AmonyConfig) extends Logging {

  def scanMedia(mediaPath: Path, hash: Option[String], config: MediaLibConfig): Task[Media] = {

    FFMpeg
      .ffprobe(mediaPath, false, appConfig.ffprobeTimeout).map { case probe =>

        val fileHash = hash.getOrElse(config.hashingAlgorithm.generateHash(mediaPath))

        val mainVideoStream =
          probe.firstVideoStream.getOrElse(throw new IllegalStateException(s"No video stream found for: ${mediaPath}"))

        logger.debug(mainVideoStream.toString)

        probe.debugOutput.foreach { debug =>
          if (!debug.isFastStart)
            logger.warn(s"Video is not optimized for streaming: ${mediaPath}")
        }

        val fileAttributes = Files.readAttributes(mediaPath, classOf[BasicFileAttributes])

        val timeStamp = mainVideoStream.durationMillis / 3

        val fileInfo = FileInfo(
          relativePath     = config.mediaPath.relativize(mediaPath).toString,
          hash             = fileHash,
          size             = fileAttributes.size(),
          creationTime     = fileAttributes.creationTime().toMillis,
          lastModifiedTime = fileAttributes.lastModifiedTime().toMillis
        )

        val videoInfo = VideoInfo(
          mainVideoStream.fps,
          mainVideoStream.durationMillis,
          (mainVideoStream.width, mainVideoStream.height)
        )

        val fragmentLength = config.defaultFragmentLength.toMillis

        Media(
          id                 = fileHash,
          title              = None,
          comment            = None,
          fileInfo           = fileInfo,
          videoInfo          = videoInfo,
          thumbnailTimestamp = timeStamp,
          fragments          = List(Fragment(timeStamp, timeStamp + fragmentLength, None, List.empty)),
          tags               = Set.empty
        )
      }
  }

  def scanMediaInDirectory(
      config: MediaLibConfig,
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): (Observable[Media], Observable[Media]) = {

    val files = FileUtil.walkDir(config.mediaPath)

    logger.info("Scanning directory for media...")

    // first calculate the hashes
    val filesWithHashes: List[(Path, String)] = Observable
      .from(files)
      .filter { file => config.filterFileName(file.getFileName.toString) }
      .mapParallelUnordered(config.scanParallelFactor) { path =>
        Task {
          val hash = if (config.verifyExistingHashes) {
            config.hashingAlgorithm.generateHash(path)
          } else {
            val relativePath = config.mediaPath.relativize(path).toString

            persistedMedia.find(_.fileInfo.relativePath == relativePath) match {
              case None    => config.hashingAlgorithm.generateHash(path)
              case Some(m) =>
                val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

                if (m.fileInfo.lastModifiedTime != fileAttributes.lastModifiedTime().toMillis) {
                  logger.warn(s"$path last modified time is different from what last seen, recomputing hash")
                  config.hashingAlgorithm.generateHash(path)
                } else {
                  m.fileInfo.hash
                }
            }
          }

          (path, hash)
        }
      }
      .consumeWith(Consumer.toList)
      .runSyncUnsafe()

    logger.info(s"Scanning done, found ${filesWithHashes.size} files")

    // warn about hash collisions
    val collisionsGroupedByHash = filesWithHashes
      .groupBy { case (_, hash) => hash }
      .filter { case (_, files) => files.size > 1 }

    collisionsGroupedByHash.foreach { case (hash, files) =>
      val collidingFiles = files.map(_._1.absoluteFileName()).mkString("\n")
      logger.warn(s"The following files share the same hash and will be ignored ($hash):\n$collidingFiles")
    }

    val collidingHashes = collisionsGroupedByHash.map { case (hash, _) => hash }.toSet

    val (remaining, removed) =
      persistedMedia.partition(m => filesWithHashes.exists { case (_, hash) => hash == m.fileInfo.hash })

    // moved and new
    val newAndMoved: Observable[Media] = Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        // filters existing, unchanged files
        remaining.exists(m =>
          m.fileInfo.hash == hash && m.fileInfo.relativePath == config.mediaPath.relativize(path).toString
        )
      }
      .filterNot { case (_, hash) => collidingHashes.contains(hash) }
      .mapParallelUnordered[Option[Media]](config.scanParallelFactor) { case (videoFile, hash) =>
          val relativePath = config.mediaPath.relativize(videoFile).toString

          remaining.find(_.fileInfo.hash == hash) match {
            case Some(old) =>
              logger.info(s"File was moved: '${old.fileInfo.relativePath}' -> '${relativePath}'")
              Task.now(Some(old.copy(fileInfo = old.fileInfo.copy(relativePath = relativePath))))

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")
              scanMedia(videoFile, Some(hash), config)
                .map(m => Some(m))
                .onErrorHandle { e =>
                  logger.warn(s"Failed to scan video: $videoFile", e)
                  None
                }
        }
      }.collect {
        case Some(m) => m
      }

    (Observable.from(removed), newAndMoved)
  }
}
