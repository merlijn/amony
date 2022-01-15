package nl.amony.lib

import akka.util.Timeout
import nl.amony.{AmonyConfig, MediaLibConfig, PreviewConfig, TranscodeSettings}
import nl.amony.actor.media.MediaLibProtocol.FileInfo
import nl.amony.actor.media.MediaLibProtocol.Fragment
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.media.MediaLibProtocol.VideoInfo
import nl.amony.lib.FileUtil.PathOps
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Consumer
import monix.reactive.Observable
import nl.amony.lib.ffmpeg.FFMpeg
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt
import scala.util.Success

class MediaScanner(appConfig: AmonyConfig) extends Logging {

  def filterFileName(fileName: String): Boolean = {
    fileName.endsWith(".mp4") && !fileName.startsWith(".")
  }

  def convertNonStreamableVideos(api: AmonyApi): Unit = {

    val files = FileUtil.walkDir(appConfig.media.path)

    implicit val timeout = Timeout(3.seconds)
    implicit val ec      = scala.concurrent.ExecutionContext.global
    val parallelism      = appConfig.media.scanParallelFactor

    Observable
      .fromIterable(files)
      .mapParallelUnordered(parallelism)(path => FFMpeg.ffprobe(path, true, appConfig.ffprobeTimeout).map(p => path -> p))
      .filterNot { case (_, probe) => probe.debugOutput.exists(_.isFastStart) }
      .filterNot { case (path, _) => filterFileName(path.getFileName().toString) }
      .mapParallelUnordered(parallelism) { case (videoWithoutFastStart, _) => Task {

          logger.info(s"Creating faststart/streamable mp4 for: ${videoWithoutFastStart}")

          val out = FFMpeg.addFastStart(videoWithoutFastStart)
          val oldHash = appConfig.media.hashingAlgorithm.generateHash(videoWithoutFastStart)
          val newHash = appConfig.media.hashingAlgorithm.generateHash(out)

          logger.info(s"$oldHash -> $newHash: ${appConfig.media.mediaPath.relativize(out).toString}")

          api.query.getById(oldHash).onComplete {
            case Success(Some(v)) =>
              val m = v.copy(
                id = newHash,
                fileInfo = v.fileInfo.copy(hash = newHash, relativePath = appConfig.media.mediaPath.relativize(out).toString)
              )

              api.modify.upsertMedia(m).foreach { _ =>
                api.admin.regeneratePreviewForMedia(m)
                api.modify.deleteMedia(oldHash, deleteResource = false)
                videoWithoutFastStart.deleteIfExists()
              }
            case other =>
              logger.warn(s"Unexpected result: $other")
          }
        }
      }
  }

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

        val media = Media(
          id                 = fileHash,
          title              = None,
          comment            = None,
          fileInfo           = fileInfo,
          videoInfo          = videoInfo,
          thumbnailTimestamp = timeStamp,
          fragments          = List(Fragment(timeStamp, timeStamp + fragmentLength, None, List.empty)),
          tags               = Set.empty
        )

        createPreviews(
          media,
          mediaPath,
          timeStamp,
          timeStamp + fragmentLength,
          config.previews
        )

        media
      }
  }

  def scanVideosInDirectory(
      config: MediaLibConfig,
      persistedMedia: List[Media]
  )(implicit s: Scheduler, timeout: Timeout): (Observable[Media], Observable[Media]) = {

    val files = FileUtil.walkDir(config.mediaPath)

    logger.info("Scanning directory for media...")

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

  def deleteVideoFragment(
    media: Media,
    from: Long,
    to: Long,
    previewConfig: PreviewConfig
  ): Unit = {

    (appConfig.media.resourcePath / s"${media.id}-$from-${to}_${media.height}p.mp4").deleteIfExists()

    previewConfig.transcode.foreach { transcode =>
      (appConfig.media.resourcePath / s"${media.id}-${from}_${transcode.scaleHeight}p.webp").deleteIfExists()
      (appConfig.media.resourcePath / s"${media.id}-$from-${to}_${transcode.scaleHeight}p.mp4").deleteIfExists()
    }
  }

  def createPreviews(
      media: Media,
      videoPath: Path,
      from: Long,
      to: Long,
      config: PreviewConfig
  ): Unit = {

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
