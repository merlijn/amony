package nl.amony.webserver.admin

import akka.util.Timeout
import monix.eval.Task
import monix.reactive.Observable
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.{FileUtil, PathOps}
import nl.amony.service.media.MediaService
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import scribe.Logging

import scala.concurrent.duration.DurationInt
import scala.util.Success

object ConvertNonStreamableVideos extends Logging {

  def convertNonStreamableVideos(config: LocalResourcesConfig, api: MediaService, adminApi: AdminApi): Unit = {

    val files = FileUtil.listFilesInDirectoryRecursive(config.mediaPath)

    implicit val timeout = Timeout(3.seconds)
    implicit val ec      = scala.concurrent.ExecutionContext.global
    val parallelism      = config.scanParallelFactor

    Observable
      .fromIterable(files)
      .mapParallelUnordered(parallelism)(path => FFMpeg.ffprobe(path, true).map(p => path -> p))
      .filterNot { case (_, probe) => probe.debugOutput.exists(_.isFastStart) }
      .filterNot { case (path, _) => config.filterFileName(path.getFileName().toString) }
      .mapParallelUnordered(parallelism) { case (video, _) =>

        FFMpeg.addFastStart(video).map { videoWithFaststart =>
          logger.info(s"Creating faststart/streamable mp4 for: ${video}")

          val oldHash = config.hashingAlgorithm.createHash(video)
          val newHash = config.hashingAlgorithm.createHash(videoWithFaststart)

          logger.info(s"$oldHash -> $newHash: ${config.mediaPath.relativize(videoWithFaststart).toString}")

          api.getById(oldHash).onComplete {
            case Success(Some(v)) =>
              val m = v.copy(
                id = newHash,
                fileInfo = v.fileInfo.copy(hash = newHash, relativePath = config.mediaPath.relativize(videoWithFaststart).toString)
              )

              api.upsertMedia(m).foreach { _ =>
                adminApi.regeneratePreviewForMedia(m)
                api.deleteMedia(oldHash, deleteResource = false)
                video.deleteIfExists()
              }
            case other =>
              logger.warn(s"Unexpected result: $other")
          }
        }
      }
  }

}
