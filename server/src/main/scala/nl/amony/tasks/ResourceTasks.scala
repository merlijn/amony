package nl.amony.tasks

import better.files.File.apply
import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import nl.amony.{MediaLibConfig, TranscodeSettings}
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.FileUtil._
import scribe.Logging

object ResourceTasks extends Logging {

  private def generatePreviews(config: MediaLibConfig, media: Media, from: Long, to: Long, height: Int, crf: Int, overwrite: Boolean): Task[Unit] = {

    Task {

      val input = config.mediaPath.resolve(media.fileInfo.relativePath)
      val thumbnailOut = config.resourcePath.resolve(s"${media.id}-${from}_${height}p.webp")

      if (!thumbnailOut.exists || overwrite)
        FFMpeg.writeThumbnail(
          inputFile = input,
          timestamp = from,
          outputFile = Some(thumbnailOut),
          scaleHeight = Some(height)
        )

      val fragmentOut = config.resourcePath.resolve(s"${media.id}-$from-${to}_${height}p.mp4")

      if (!fragmentOut.exists || overwrite)
        FFMpeg.transcodeToMp4(
          inputFile = input,
          from = from,
          to = to,
          outputFile = Some(fragmentOut),
          quality = crf,
          scaleHeight = Some(height)
        )
    }
  }

  def createFragments(config: MediaLibConfig, media: Media, overwrite: Boolean = false): Task[Unit] = {
    Observable
      .fromIterable(media.fragments)
      .consumeWith(Consumer.foreachTask(f => createFragment(config, media, f.fromTimestamp, f.toTimestamp, overwrite)))
  }

  def createFragment(config: MediaLibConfig,
                     media: Media,
                     from: Long,
                     to: Long,
                     overwrite: Boolean = false): Task[Unit] = {
    val extra: Option[TranscodeSettings] =
      if (media.height < config.previews.transcode.map(_.scaleHeight).min)
        Some(TranscodeSettings("mp4", media.height, 23))
      else None

    val transcodes = (extra.toList ::: config.previews.transcode).filterNot(_.scaleHeight > media.height)

    Observable
      .fromIterable(transcodes)
      .consumeWith(Consumer.foreachTask(t => generatePreviews(config, media, from, to, t.scaleHeight, t.crf, overwrite)))
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
