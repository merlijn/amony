package nl.amony.lib

import akka.util.Timeout
import nl.amony.{MediaLibConfig, PreviewConfig}
import nl.amony.actor.MediaLibProtocol.{FileInfo, Fragment, Media, VideoInfo}
import nl.amony.http.JsonCodecs
import nl.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.util.Success

object MediaLibScanner extends Logging with JsonCodecs {

  val fragmentLength = 3000

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
            val m = v.copy(
              id       = newHash,
              fileInfo = v.fileInfo.copy(hash = newHash, relativePath = config.libraryPath.relativize(out).toString)
            )

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

  def scanVideo(hash: String, baseDir: Path, videoPath: Path, config: MediaLibConfig): Media = {

    val ffprobeInfo = FFMpeg.ffprobe(videoPath)

    if (!ffprobeInfo.fastStart)
      logger.warn(s"Video is not optimized for streaming: ${videoPath}")

    val fileAttributes = Files.readAttributes(videoPath, classOf[BasicFileAttributes])

    val timeStamp = ffprobeInfo.duration / 3
    createVideoFragment(videoPath, config.indexPath, hash, timeStamp, timeStamp + fragmentLength, config.previews)

    val fileInfo = FileInfo(
      relativePath     = baseDir.relativize(videoPath).toString,
      hash             = hash,
      size             = fileAttributes.size(),
      creationTime     = fileAttributes.creationTime().toMillis,
      lastModifiedTime = fileAttributes.lastModifiedTime().toMillis
    )

    val videoInfo = VideoInfo(
      ffprobeInfo.fps,
      ffprobeInfo.duration,
      ffprobeInfo.resolution
    )

    Media(
      id                 = hash,
      title              = None,
      comment            = None,
      fileInfo           = fileInfo,
      videoInfo          = videoInfo,
      thumbnailTimestamp = timeStamp,
      fragments          = List(Fragment(timeStamp, timeStamp + fragmentLength, None, List.empty)),
      tags               = Set.empty
    )
  }

  def scanVideosInDirectory(
      config: MediaLibConfig,
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): (Observable[Media], Observable[Media]) = {

    val files = FileUtil.walkDir(config.libraryPath)

    logger.info("Scanning directory for files & calculating hashes...")

    // first calculate the hashes
    val filesWithHashes: List[(Path, String)] = Observable
      .from(files)
      .filter { file => filterFileName(file.getFileName.toString) }
      .mapParallelUnordered(config.scanParallelFactor) { path =>
        Task {
          val hash = if (config.verifyExistingHashes) {
            config.hashingAlgorithm.generateHash(path)
          } else {
            val relativePath = config.libraryPath.relativize(path).toString

            persistedMedia.find(_.fileInfo.relativePath == relativePath) match {
              case None => config.hashingAlgorithm.generateHash(path)
              case Some(m) =>
                val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

                if (m.fileInfo.lastModifiedTime != fileAttributes.lastModifiedTime().toMillis) {
                  logger.warn(s"$path was modified since last seen, recomputing hash")
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

    // warn about hash collisions
    val collisionsGroupedByHash = filesWithHashes
      .groupBy { case (_, hash) => hash }
      .filter { case (_, files) => files.size > 1 }

    collisionsGroupedByHash.foreach {
      case (hash, files) =>
        val collidingFiles = files.map(_._1.absoluteFileName()).mkString("\n")
        logger.warn(s"The following files share the same hash and will be ignored ($hash):\n$collidingFiles")
    }

    val collidingHashes = collisionsGroupedByHash.map { case (hash, _) => hash }.toSet

    val (remaining, removed) =
      persistedMedia.partition(m => filesWithHashes.exists { case (_, hash) => hash == m.fileInfo.hash })

    logger.info(s"Scanning done, found ${filesWithHashes.size} files")

    // moved and new
    val newAndMoved = Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        // filters existing, unchanged files
        remaining.exists(m =>
          m.fileInfo.hash == hash && m.fileInfo.relativePath == config.libraryPath.relativize(path).toString
        )
      }
      .filterNot { case (_, hash) => collidingHashes.contains(hash) }
      .mapParallelUnordered[Media](config.scanParallelFactor) { case (videoFile, hash) =>
        Task {
          val relativePath = config.libraryPath.relativize(videoFile).toString

          remaining.find(_.fileInfo.hash == hash) match {
            case Some(old) =>
              logger.info(s"File was moved: '${old.fileInfo.relativePath}' -> '${relativePath}'")
              old.copy(fileInfo = old.fileInfo.copy(relativePath = relativePath))

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")
              scanVideo(hash, config.libraryPath, videoFile, config)
          }
        }
      }

    (Observable.from(removed), newAndMoved)
  }

  def deleteVideoFragment(indexPath: Path, id: String, from: Long, to: Long): Unit = {

    (indexPath / "thumbnails" / s"${id}-$from.webp").deleteIfExists()
    (indexPath / "thumbnails" / s"${id}-$from-$to.mp4").deleteIfExists()
  }

  def createVideoFragment(videoPath: Path, indexPath: Path, id: String, from: Long, to: Long, config: PreviewConfig): Unit = {

    val thumbnailPath = s"${indexPath}/thumbnails"

    Files.createDirectories(indexPath.resolve("thumbnails"))

    FFMpeg.writeThumbnail(
      inputFile   = videoPath.absoluteFileName(),
      timestamp   = from,
      outputFile  = Some(s"${thumbnailPath}/${id}-$from.webp"),
      scaleHeight = Some(config.scaleHeight)
    )

    FFMpeg.writeMp4(
      inputFile   = videoPath.absoluteFileName(),
      from        = from,
      to          = to,
      outputFile  = Some(s"${thumbnailPath}/${id}-$from-$to.mp4"),
      quality     = config.videoCrf,
      scaleHeight = Some(config.scaleHeight)
    )
  }
}
