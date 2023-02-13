package nl.amony.service.media.tasks

import akka.util.Timeout
import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.{FileUtil, PathOps}
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.media.MediaService
import scribe.Logging
import fs2.Stream

import scala.concurrent.duration.DurationInt
import scala.util.Success

object ConvertNonStreamableVideos extends Logging {

  def convertNonStreamableVideos(config: LocalResourcesConfig, mediaService: MediaService): Unit = {

    val files = FileUtil.listFilesInDirectoryRecursive(config.mediaPath)

    implicit val timeout = Timeout(3.seconds)
    implicit val ec      = scala.concurrent.ExecutionContext.global
    val parallelism      = config.scanParallelFactor

    Stream.fromIterator[IO](files.iterator, 10)
      .parEvalMapUnordered(parallelism)(path => FFMpeg.ffprobe(path, true).map(p => path -> p))
      .filter { case (_, probe) => !probe.debugOutput.exists(_.isFastStart) }
      .filter { case (path, _) => !config.filterFileName(path.getFileName().toString) }
      .parEvalMapUnordered(parallelism) { case (video, _) =>

        FFMpeg.addFastStart(video).map { videoWithFaststart =>
          logger.info(s"Creating faststart/streamable mp4 for: ${video}")

          val oldHash = config.hashingAlgorithm.createHash(video)
          val newHash = config.hashingAlgorithm.createHash(videoWithFaststart)

          logger.info(s"$oldHash -> $newHash: ${config.mediaPath.relativize(videoWithFaststart).toString}")

          mediaService.getById(oldHash).onComplete {
            case Success(Some(v)) =>
              val m = v.copy(
                mediaId = newHash,
                resourceInfo = v.resourceInfo.copy(hash = newHash, relativePath = config.mediaPath.relativize(videoWithFaststart).toString)
              )

              mediaService.upsertMedia(m).foreach { _ =>
//                adminApi.regeneratePreviewForMedia(m)
                mediaService.deleteMedia(oldHash, deleteResource = false)
                video.deleteIfExists()
              }
            case other =>
              logger.warn(s"Unexpected result: $other")
          }
        }
      }
  }

}
