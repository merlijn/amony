package nl.amony.lib

import akka.util.Timeout
import nl.amony.{AmonyConfig, MediaLibConfig, PreviewConfig, TranscodeSettings}
import nl.amony.actor.MediaLibProtocol.FileInfo
import nl.amony.actor.MediaLibProtocol.Fragment
import nl.amony.actor.MediaLibProtocol.Media
import nl.amony.actor.MediaLibProtocol.VideoInfo
import nl.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Consumer
import monix.reactive.Observable
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt
import scala.util.Success

class MediaScanner(appConfig: AmonyConfig) extends Logging {

  val defaultFragmentLength = 3000

  def filterFileName(fileName: String): Boolean = {
    fileName.endsWith(".mp4") && !fileName.startsWith(".")
  }

  def convertNonStreamableVideos(api: AmonyApi): Unit = {

    val files = FileUtil.walkDir(appConfig.media.path)

    implicit val timeout: Timeout = Timeout(3.seconds)
    implicit val ec               = scala.concurrent.ExecutionContext.global

    files
      .filter { vid =>
        // filter for extension
        filterFileName(vid.getFileName().toString) && !FFMpeg.ffprobe(vid)._2.isFastStart
      }
      .foreach { videoWithoutFastStart =>
        logger.info(s"Creating faststart/streamable mp4 for: ${videoWithoutFastStart}")

        val out     = FFMpeg.addFastStart(videoWithoutFastStart)
        val oldHash = appConfig.media.hashingAlgorithm.generateHash(videoWithoutFastStart)
        val newHash = appConfig.media.hashingAlgorithm.generateHash(out)

        logger.info(s"$oldHash -> $newHash: ${appConfig.media.mediaPath.relativize(out).toString}")

        api.query.getById(oldHash).onComplete {
          case Success(Some(v)) =>
            val m = v.copy(
              id       = newHash,
              fileInfo = v.fileInfo.copy(hash = newHash, relativePath = appConfig.media.mediaPath.relativize(out).toString)
            )

            api.modify.upsertMedia(m).foreach { _ =>
              api.admin.regeneratePreviewForMedia(m)
              api.modify.deleteMedia(oldHash, deleteFile = false)
              videoWithoutFastStart.deleteIfExists()
            }
          case other =>
            logger.warn(s"Unexpected result: $other")
        }
      }
  }

  def scanVideo(videoPath: Path, hash: Option[String], config: MediaLibConfig): Media = {

    val (probe, debug) = FFMpeg.ffprobe(videoPath)

    val fileHash = hash.getOrElse(config.hashingAlgorithm.generateHash(videoPath))

    val mainStream =
      probe.firstVideoStream.getOrElse(throw new IllegalStateException(s"No video stream found for: ${videoPath}"))

    logger.debug(mainStream.toString)

    if (!debug.isFastStart)
      logger.warn(s"Video is not optimized for streaming: ${videoPath}")

    val fileAttributes = Files.readAttributes(videoPath, classOf[BasicFileAttributes])

    val timeStamp = mainStream.durationMillis / 3

    val fileInfo = FileInfo(
      relativePath     = config.mediaPath.relativize(videoPath).toString,
      hash             = fileHash,
      size             = fileAttributes.size(),
      creationTime     = fileAttributes.creationTime().toMillis,
      lastModifiedTime = fileAttributes.lastModifiedTime().toMillis
    )

    val videoInfo = VideoInfo(
      mainStream.fps,
      mainStream.durationMillis,
      (mainStream.width, mainStream.height)
    )

    val media = Media(
      id                 = fileHash,
      title              = None,
      comment            = None,
      fileInfo           = fileInfo,
      videoInfo          = videoInfo,
      thumbnailTimestamp = timeStamp,
      fragments          = List(Fragment(timeStamp, timeStamp + defaultFragmentLength, None, List.empty)),
      tags               = Set.empty
    )

    createPreviews(
      media,
      videoPath,
      timeStamp,
      timeStamp + defaultFragmentLength,
      config.previews
    )

    media
  }

  def scanVideosInDirectory(
      config: MediaLibConfig,
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): (Observable[Media], Observable[Media]) = {

    val files = FileUtil.walkDir(config.mediaPath)

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
            val relativePath = config.mediaPath.relativize(path).toString

            persistedMedia.find(_.fileInfo.relativePath == relativePath) match {
              case None => config.hashingAlgorithm.generateHash(path)
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

    logger.info(s"Scanning done, found ${filesWithHashes.size} files")

    // moved and new
    val newAndMoved = Observable
      .from(filesWithHashes)
      .filterNot { case (path, hash) =>
        // filters existing, unchanged files
        remaining.exists(m =>
          m.fileInfo.hash == hash && m.fileInfo.relativePath == config.mediaPath.relativize(path).toString
        )
      }
      .filterNot { case (_, hash) => collidingHashes.contains(hash) }
      .mapParallelUnordered[Media](config.scanParallelFactor) { case (videoFile, hash) =>
        Task {
          val relativePath = config.mediaPath.relativize(videoFile).toString

          remaining.find(_.fileInfo.hash == hash) match {
            case Some(old) =>
              logger.info(s"File was moved: '${old.fileInfo.relativePath}' -> '${relativePath}'")
              old.copy(fileInfo = old.fileInfo.copy(relativePath = relativePath))

            case None =>
              logger.info(s"Scanning new file: '${relativePath}'")
              scanVideo(videoFile, Some(hash), config)
          }
        }
      }

    (Observable.from(removed), newAndMoved)
  }

  def deleteVideoFragment(
    media: Media,
    id: String,
    from: Long,
    to: Long,
    previewConfig: PreviewConfig
  ): Unit = {

    (appConfig.media.resourcePath / s"${id}-$from-${to}_${media.height}p.mp4").deleteIfExists()

    previewConfig.transcode.foreach { transcode =>
      (appConfig.media.resourcePath / s"${id}-${from}_${transcode.scaleHeight}p.webp").deleteIfExists()
      (appConfig.media.resourcePath / s"${id}-$from-${to}_${transcode.scaleHeight}p.mp4").deleteIfExists()
    }
  }

  def createPreviews(
      media: Media,
      videoPath: Path,
      from: Long,
      to: Long,
      config: PreviewConfig
  ): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    def genFor(height: Int, crf: Int) = {
      FFMpeg.writeThumbnail(
        inputFile   = videoPath,
        timestamp   = from,
        outputFile  = Some(appConfig.media.resourcePath.resolve(s"${media.id}-${from}_${height}p.webp")),
        scaleHeight = Some(height)
      )

      FFMpeg.transcodeToMp4(
        inputFile   = videoPath,
        from        = from,
        to          = to,
        outputFile  = Some(appConfig.media.resourcePath.resolve(s"${media.id}-$from-${to}_${height}p.mp4")),
        quality     = crf,
        scaleHeight = Some(height)
      )
    }

    config.transcode.filterNot(_.scaleHeight > media.height).foreach { transcode =>
      genFor(transcode.scaleHeight, transcode.crf)
    }

    if (media.height < config.transcode.map(_.scaleHeight).min)
      genFor(media.height, 23)
  }
}
