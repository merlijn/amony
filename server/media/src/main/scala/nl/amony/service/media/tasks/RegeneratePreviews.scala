package nl.amony.service.media.tasks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.media.MediaService
import scribe.Logging
import fs2.Stream
import nl.amony.service.media.MediaProtocol.Media
import scala.util.control.NonFatal

object RegeneratePreviews extends Logging {
  def regeneratePreviewSprites(config: LocalResourcesConfig, mediaService: MediaService)(implicit IORuntime: IORuntime) = {
    Stream
      .evals[IO, Seq, Media](IO.fromFuture(IO(mediaService.getAll())))
      .foreach { m =>
        logger.info(s"generating thumbnail previews for '${m.fileName()}'")
        FFMpeg.createThumbnailTile(
          inputFile      = m.resolvePath(config.mediaPath).toAbsolutePath,
          outputDir      = config.resourcePath,
          outputBaseName = Some(s"${m.id}-timeline"),
          overwrite      = false
        ).onError {
          case NonFatal(e) => IO { logger.warn(s"Failed to generate preview sprite for ${m.fileName()}", e) }
        }
      }.compile.drain.unsafeRunSync()
  }
}
