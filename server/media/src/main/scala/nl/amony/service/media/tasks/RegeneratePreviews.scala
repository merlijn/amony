package nl.amony.service.media.tasks

import monix.execution.Scheduler
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.media.MediaService
import scribe.Logging

import scala.util.control.NonFatal

object RegeneratePreviews extends Logging {
  def regeneratePreviewSprites(config: LocalResourcesConfig, mediaService: MediaService)(implicit scheduler: Scheduler) = {
    mediaService.getAll().foreach { medias =>
      medias.foreach { m =>
        logger.info(s"generating thumbnail previews for '${m.fileName()}'")
        try {
          FFMpeg.createThumbnailTile(
            inputFile      = m.resolvePath(config.mediaPath).toAbsolutePath,
            outputDir      = config.resourcePath,
            outputBaseName = Some(s"${m.id}-timeline"),
            overwrite      = false
          ).runSyncUnsafe()
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Failed to generate preview sprite for ${m.fileName()}", e)
        }
      }
    }
  }
}
