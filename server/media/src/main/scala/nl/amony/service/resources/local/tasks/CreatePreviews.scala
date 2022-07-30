package nl.amony.service.resources.local.tasks

import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.media.MediaConfig.{LocalResourcesConfig, TranscodeSettings}
import nl.amony.service.media.actor.MediaLibProtocol.Media

import java.nio.file.{Files, Path}

object CreatePreviews {

  private[resources] def createPreviewSprite(
        config: LocalResourcesConfig,
        media: Media,
        overwrite: Boolean = false): Task[Unit] = {
    FFMpeg.createThumbnailTile(
      inputFile      = media.resolvePath(config.mediaPath).toAbsolutePath,
      outputDir      = config.resourcePath,
      outputBaseName = Some(s"${media.id}-timeline"),
      overwrite      = overwrite
    )
  }

  def createVideoPreviews(
          config: LocalResourcesConfig,
          media: Media,
          overwrite: Boolean = false
        ): Task[Unit] = {
    Observable
      .fromIterable(media.fragments)
      .consumeWith(Consumer.foreachTask(f => createVideoPreview(config, media, (f.start, f.end), overwrite)))
  }

  def createVideoPreview(
         config: LocalResourcesConfig,
         media: Media,
         range: (Long, Long),
         overwrite: Boolean = false
       ): Task[Unit] = {

    val (from, to) = range

    val transcodeList =
      if (media.height < config.transcode.map(_.scaleHeight).min)
        List(TranscodeSettings("mp4", media.height, 23))
      else
        config.transcode.filterNot(_.scaleHeight > media.height)

    Observable
      .fromIterable(transcodeList)
      .consumeWith(
        Consumer.foreachTask { t =>

          val input = config.mediaPath.resolve(media.resourceInfo.relativePath)
          val output = config.resourcePath.resolve(s"${media.id}-${from}-${to}_${t.scaleHeight}p.mp4")
          writeFragment(input, output, range, t.scaleHeight, t.crf, overwrite)
        }
      )
  }

  def writeFragment(input: Path, output: Path, range: (Long, Long), height: Int, crf: Int, overwrite: Boolean): Task[Unit] = {
    if (!Files.exists(output) || overwrite)
      FFMpeg.transcodeToMp4(
        inputFile   = input,
        range       = range,
        outputFile  = Some(output),
        quality     = crf,
        scaleHeight = Some(height)
      )
    else
      Task.unit
  }
}
