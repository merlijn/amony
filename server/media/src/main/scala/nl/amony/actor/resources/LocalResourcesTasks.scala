package nl.amony.actor.resources

import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import nl.amony.actor.media.MediaConfig.{MediaLibConfig, TranscodeSettings}
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.lib.FileUtil.PathOps
import nl.amony.lib.ffmpeg.FFMpeg
import scribe.Logging

import java.nio.file.{Files, Path}

object LocalResourcesTasks extends Logging {

  private[resources] def createPreview(config: MediaLibConfig,
                                       media: Media,
                                       range: (Long, Long),
                                       overwrite: Boolean = false): Task[Unit] = {

    val (from, to) = range

    val transcodeList =
      if (media.height < config.previews.transcode.map(_.scaleHeight).min)
        List(TranscodeSettings("mp4", media.height, 23))
      else
        config.previews.transcode.filterNot(_.scaleHeight > media.height)

    def writeFragment(input: Path, height: Int, crf: Int): Unit = {
      val output = config.resourcePath.resolve(s"${media.id}-${from}-${to}_${height}p.mp4")
      if (!Files.exists(output) || overwrite)
        FFMpeg.transcodeToMp4(
          inputFile   = input,
          range       = range,
          outputFile  = Some(output),
          quality     = crf,
          scaleHeight = Some(height)
        )
    }

    def writeThumbnail(input: Path, height: Int): Unit = {
      val output = config.resourcePath.resolve(s"${media.id}-${from}_${height}p.webp")
      if (!Files.exists(output) || overwrite)
        FFMpeg.writeThumbnail(
          inputFile   = input,
          timestamp   = from,
          outputFile  = Some(output),
          scaleHeight = Some(height)
        )
    }

    Observable
      .fromIterable(transcodeList)
      .consumeWith(Consumer.foreachTask(t => Task {
        val input = config.mediaPath.resolve(media.fileInfo.relativePath)
        writeThumbnail(input, t.scaleHeight)
        writeFragment(input, t.scaleHeight, t.crf)
      }))
  }

  private[resources] def createFragments(config: MediaLibConfig, media: Media, overwrite: Boolean = false): Task[Unit] = {
    Observable
      .fromIterable(media.fragments)
      .consumeWith(Consumer.foreachTask(f => createPreview(config, media, (f.fromTimestamp, f.toTimestamp), overwrite)))
  }

  def deleteVideoFragment(mediaLibConfig: MediaLibConfig,
                          media: Media,
                          from: Long,
                          to: Long): Unit = {

    mediaLibConfig.resourcePath.resolve(s"${media.id}-$from-${to}_${media.height}p.mp4").deleteIfExists()

    mediaLibConfig.previews.transcode.foreach { transcode =>
      mediaLibConfig.resourcePath.resolve(s"${media.id}-${from}_${transcode.scaleHeight}p.webp").deleteIfExists()
      mediaLibConfig.resourcePath.resolve(s"${media.id}-$from-${to}_${transcode.scaleHeight}p.mp4").deleteIfExists()
    }
  }
}
