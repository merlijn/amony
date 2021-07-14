package com.github.merlijn.kagera.lib

import better.files.File
import com.github.merlijn.kagera.actor.MediaLibActor.{Collection, Media, Thumbnail}
import com.github.merlijn.kagera.http.JsonCodecs
import com.github.merlijn.kagera.lib.FFMpeg.Probe
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.Path

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    max: Option[Int]
)

object MediaLibScanner extends Logging with JsonCodecs {

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Media = {
    logger.info(s"Scanning file: ${videoPath.toAbsolutePath}")

    val info      = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3

    generateThumbnail(videoPath, indexDir, info.id, timeStamp)

    val video     = asVideo(baseDir, info, timeStamp)

    video
  }

  def scanPath(
      scanPath: Path,
      indexPath: Path,
      parallelFactor: Int,
      max: Option[Int],
      last: List[Media],
      extensions: List[String] = List("mp4", "webm")
  )(implicit s: Scheduler): List[Media] = {

    val files = FileUtil.walkDir(scanPath)

    logger.info(s"max: $max")

    val truncatedMaybe = max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    val obs = Observable
      .from(truncatedMaybe)
      .filter { vid =>
        val fileName = vid.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .filter { vid =>
        val relativePath   = scanPath.relativize(vid).toString
        val alreadyIndexed = last.exists(_.uri == relativePath)

        if (alreadyIndexed)
          logger.info(s"Skipping already indexed: ${relativePath}")

        !alreadyIndexed
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanVideo(scanPath, p, indexPath) } }

    val c = Consumer.toList[Media]

    obs.consumeWith(c).runSyncUnsafe()
  }

  def generateThumbnail(videoPath: Path, outputDir: Path, id: String, timestamp: Long): Unit = {

    val thumbnailPath = s"${outputDir}/thumbnails"
    val thumbnailDir  = File(thumbnailPath)

    if (!thumbnailDir.exists)
      thumbnailDir.createDirectory()

    val inputFile = videoPath.toAbsolutePath.toString

    FFMpeg.writeThumbnail(
      inputFile = inputFile,
      timestamp = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp.jpeg")
    )

    FFMpeg.createWebP(
      inputFile = inputFile,
      timestamp = timestamp,
      outputFile = Some(s"${thumbnailPath}/${id}-$timestamp.webp")
    )
  }

  protected def asVideo(baseDir: Path, info: Probe, thumbnailTimestamp: Long): Media = {

    val relativePath = baseDir.relativize(Path.of(info.fileName)).toString

    val slashIdx = relativePath.lastIndexOf('/')
    val dotIdx   = relativePath.lastIndexOf('.')

    val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
    val endIdx   = if (dotIdx >= 0) dotIdx else relativePath.length

    val title = relativePath.substring(startIdx, endIdx)

    Media(
      id = info.id,
      uri = relativePath,
      title = title,
      duration = info.duration,
      thumbnail = Thumbnail(thumbnailTimestamp),
      tags = Seq.empty,
      resolution = info.resolution
    )
  }

  def scan(config: MediaLibConfig, last: List[Media]): (List[Media], List[Collection]) = {

    val libraryDir = File(config.libraryPath)

    // create the index directory if it does not exist
    val indexDir = File(config.indexPath)
    if (!indexDir.exists)
      indexDir.createDirectory()

    val videoIndex: List[Media] = {

      implicit val s = Scheduler.global
      val scanResult = scanPath(libraryDir.path, config.indexPath, config.scanParallelFactor, config.max, last)
      scanResult
    }

    val collections: List[Collection] = {

      val dirs = videoIndex.foldLeft(Set.empty[String]) {
        case (set, e) =>
          val parent   = (libraryDir / e.uri).parent
          val relative = s"/${libraryDir.relativize(parent)}"
          set + relative
      }

      dirs.toList.sorted.zipWithIndex.map {
        case (e, idx) => Collection(idx.toString, e)
      }
    }

    (videoIndex, collections)
  }
}
