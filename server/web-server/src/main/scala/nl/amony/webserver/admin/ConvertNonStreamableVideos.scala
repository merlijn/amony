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
      .mapParallelUnordered(parallelism)(path => FFMpeg.ffprobe(path, true, config.ffprobeTimeout).map(p => path -> p))
      .filterNot { case (_, probe) => probe.debugOutput.exists(_.isFastStart) }
      .filterNot { case (path, _) => config.filterFileName(path.getFileName().toString) }
      .mapParallelUnordered(parallelism) { case (videoWithoutFastStart, _) =>
        Task {
          logger.info(s"Creating faststart/streamable mp4 for: ${videoWithoutFastStart}")

          val out     = FFMpeg.addFastStart(videoWithoutFastStart)
          val oldHash = config.hashingAlgorithm.createHash(videoWithoutFastStart)
          val newHash = config.hashingAlgorithm.createHash(out)

          logger.info(s"$oldHash -> $newHash: ${config.mediaPath.relativize(out).toString}")

          api.getById(oldHash).onComplete {
            case Success(Some(v)) =>
              val m = v.copy(
                id       = newHash,
                fileInfo = v.fileInfo.copy(hash = newHash, relativePath = config.mediaPath.relativize(out).toString)
              )

              api.upsertMedia(m).foreach { _ =>
                adminApi.regeneratePreviewForMedia(m)
                api.deleteMedia(oldHash, deleteResource = false)
                videoWithoutFastStart.deleteIfExists()
              }
            case other =>
              logger.warn(s"Unexpected result: $other")
          }
        }
      }
  }

}
