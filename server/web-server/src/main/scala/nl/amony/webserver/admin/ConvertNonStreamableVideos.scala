package nl.amony.webserver.admin

import akka.util.Timeout
import monix.eval.Task
import monix.reactive.Observable
import nl.amony.lib.FileUtil
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaApi
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import scribe.Logging

import scala.concurrent.duration.DurationInt
import scala.util.Success

object ConvertNonStreamableVideos extends Logging {

  def convertNonStreamableVideos(config: LocalResourcesConfig, api: MediaApi, adminApi: AdminApi): Unit = {

    val files = FileUtil.walkDir(config.path)

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
          val oldHash = config.hashingAlgorithm.generateHash(videoWithoutFastStart)
          val newHash = config.hashingAlgorithm.generateHash(out)

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
