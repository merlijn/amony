package nl.amony.service.media.tasks

import cats.effect.IO
import fs2.Stream
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaServiceImpl
import nl.amony.service.media.api.{DeleteById, GetById}
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.local.RecursiveFileVisitor
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.util.Success

object ConvertNonStreamableVideos extends Logging {

  def convertNonStreamableVideos(config: LocalDirectoryConfig, mediaService: MediaServiceImpl)(implicit ec: ExecutionContext): Unit = {

    val files = RecursiveFileVisitor.listFilesInDirectoryRecursive(config.resourcePath)
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

          logger.info(s"$oldHash -> $newHash: ${config.resourcePath.relativize(videoWithFaststart).toString}")

          mediaService.getById(GetById(oldHash)).onComplete {
            case Success(v) =>
              val m = v.copy(
                mediaId = newHash,
                resourceInfo = v.resourceInfo.copy(hash = newHash, relativePath = config.resourcePath.relativize(videoWithFaststart).toString)
              )

              mediaService.upsertMedia(m).foreach { _ =>
//                adminApi.regeneratePreviewForMedia(m)
                val req = DeleteById(oldHash)
                mediaService.deleteById(req)
                video.deleteIfExists()
              }
            case other =>
              logger.warn(s"Unexpected result: $other")
          }
        }
      }
  }

}
