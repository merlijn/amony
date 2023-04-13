package nl.amony.service.media.tasks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.media.MediaServiceImpl
import scribe.Logging
import fs2.Stream
import nl.amony.service.media.api.Media

import scala.util.control.NonFatal

object RegeneratePreviews extends Logging {
  def regeneratePreviewSprites(config: LocalDirectoryConfig, mediaService: MediaServiceImpl)(implicit IORuntime: IORuntime) = {
    Stream
      .evals[IO, Seq, Media](IO.fromFuture(IO(mediaService.getAll())))
      .foreach { m =>
        logger.info(s"generating thumbnail previews for '${m.fileName()}'")
        FFMpeg.createThumbnailTile(
          inputFile      = config.mediaPath.resolve(m.resourceInfo.relativePath).toAbsolutePath,
          outputDir      = config.resourcePath,
          outputBaseName = Some(s"${m.mediaId}-timeline"),
          overwrite      = false
        ).onError {
          case NonFatal(e) => IO {
            logger.warn(s"Failed to generate preview sprite for ${m.fileName()}", e)
          }
        }
      }.compile.drain.unsafeRunSync()
  }
}
