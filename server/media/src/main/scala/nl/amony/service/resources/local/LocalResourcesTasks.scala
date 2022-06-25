package nl.amony.service.resources.local

import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaConfig.{LocalResourcesConfig, TranscodeSettings}
import nl.amony.service.media.actor.MediaLibProtocol.Media
import scribe.Logging

import java.nio.file.{Files, Path}

object LocalResourcesTasks extends Logging {

  private[resources] def createPreview(
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

    def writeFragment(input: Path, height: Int, crf: Int): Task[Unit] = {
      val output = config.resourcePath.resolve(s"${media.id}-${from}-${to}_${height}p.mp4")
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

    def writeThumbnail(input: Path, height: Int): Task[Unit] = {
      val output = config.resourcePath.resolve(s"${media.id}-${from}_${height}p.webp")
      if (!Files.exists(output) || overwrite)
        FFMpeg.createThumbnail(
          inputFile   = input,
          timestamp   = from,
          outputFile  = Some(output),
          scaleHeight = Some(height)
        )
      else
        Task.unit
    }

    Observable
      .fromIterable(transcodeList)
      .consumeWith(
        Consumer.foreachTask { t =>

          val input = config.mediaPath.resolve(media.fileInfo.relativePath)
          writeThumbnail(input, t.scaleHeight) >> writeFragment(input, t.scaleHeight, t.crf)
        }
      )
  }

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

  private[resources] def createFragments(
    config: LocalResourcesConfig,
    media: Media,
    overwrite: Boolean = false
  ): Task[Unit] = {
    Observable
      .fromIterable(media.fragments)
      .consumeWith(Consumer.foreachTask(f => createPreview(config, media, (f.fromTimestamp, f.toTimestamp), overwrite)))
  }

  def deleteVideoFragment(config: LocalResourcesConfig, media: Media, from: Long, to: Long): Unit = {

    config.resourcePath.resolve(s"${media.id}-$from-${to}_${media.height}p.mp4").deleteIfExists()

    config.transcode.foreach { transcode =>
      config.resourcePath.resolve(s"${media.id}-${from}_${transcode.scaleHeight}p.webp").deleteIfExists()
      config.resourcePath.resolve(s"${media.id}-$from-${to}_${transcode.scaleHeight}p.mp4").deleteIfExists()
    }
  }
}
