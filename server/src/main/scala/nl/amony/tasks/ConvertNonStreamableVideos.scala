package nl.amony.tasks

import akka.util.Timeout
import monix.eval.Task
import monix.reactive.Observable
import nl.amony.AmonyConfig
import nl.amony.lib.FileUtil.PathOps
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.{AmonyApi, FileUtil}
import scribe.Logging

import scala.concurrent.duration.DurationInt
import scala.util.Success

object ConvertNonStreamableVideos extends Logging {

  def convertNonStreamableVideos(appConfig: AmonyConfig, api: AmonyApi): Unit = {

    val files = FileUtil.walkDir(appConfig.media.path)

    implicit val timeout = Timeout(3.seconds)
    implicit val ec      = scala.concurrent.ExecutionContext.global
    val parallelism      = appConfig.media.scanParallelFactor

    Observable
      .fromIterable(files)
      .mapParallelUnordered(parallelism)(path => FFMpeg.ffprobe(path, true, appConfig.ffprobeTimeout).map(p => path -> p))
      .filterNot { case (_, probe) => probe.debugOutput.exists(_.isFastStart) }
      .filterNot { case (path, _) => appConfig.media.filterFileName(path.getFileName().toString) }
      .mapParallelUnordered(parallelism) { case (videoWithoutFastStart, _) =>

        Task {
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

}
