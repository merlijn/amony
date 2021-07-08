package com.github.merlijn.kagera.lib

import better.files.File
import com.github.merlijn.kagera.http.JsonCodecs
import com.github.merlijn.kagera.http.Model._
import com.github.merlijn.kagera.lib.FFMpeg.Probe
import io.circe.parser.decode
import io.circe.syntax._
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

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Video = {
    logger.info(s"Processing: ${videoPath.toAbsolutePath}")

    val info = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3

    val thumbNail = generateThumbnail(videoPath, indexDir, info.id, timeStamp)
    val video = asVideo(baseDir, info, thumbNail)

    video
  }

  def scanPath(scanPath: Path, indexPath: Path, parallelFactor: Int, max: Option[Int], extensions: List[String] = List("mp4", "webm"))(implicit s: Scheduler): List[Video] = {

    val files = FileUtil.walkDir(scanPath)

    val truncatedMaybe = max match {
      case None    => files
      case Some(n) => files.take(n)
    }

    val obs = Observable.from(truncatedMaybe)
      .filter { vid =>
        val fileName = vid.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanVideo(scanPath, p, indexPath) } }

    val c = Consumer.toList[Video]

    obs.consumeWith(c).runSyncUnsafe()
  }

  protected def readIndexFromFile(file: File): List[Video] = {
    val json = file.contentAsString

    decode[List[Video]](json) match {
      case Right(index) => index
      case Left(error) => throw error
    }
  }

  def exportIndexToFile(index: File, videos: Seq[Video]): Unit = {

    if (!index.exists)
      index.createFile()

    val json = videos.asJson.toString()
    index.overwrite(json)
  }

  def generateThumbnail(videoPath: Path, outputDir: Path, id: String, timeStamp: Long): String = {

    val thumbNail = s"${id}-$timeStamp.jpeg"
    val fullFile = s"${outputDir}/$thumbNail"

    FFMpeg.writeThumbnail(
      inputFile = videoPath.toAbsolutePath.toString,
      time = timeStamp,
      outputFile = Some(fullFile))

    thumbNail
  }

  protected def asVideo(baseDir: Path, info: Probe, thumbnail: String): Video = {

    val relativePath = baseDir.relativize(Path.of(info.fileName)).toString

    val slashIdx = relativePath.lastIndexOf('/')
    val dotIdx = relativePath.lastIndexOf('.')

    val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
    val endIdx = if (dotIdx >= 0) dotIdx else relativePath.length

    val title = relativePath.substring(startIdx, endIdx)

    Video(
      id = info.id,
      fileName = relativePath,
      title = title,
      duration = info.duration,
      thumbnail = s"/files/thumbnails/$thumbnail",
      tags = Seq.empty,
      resolution = s"${info.resolution._1}x${info.resolution._2}"
    )
  }

  def scan(config: MediaLibConfig): (List[Video], List[Collection]) = {

    val libraryDir = File(config.libraryPath)

    // create the index directory if it does not exist
    val indexDir = File(config.indexPath)
    if (!indexDir.exists)
      indexDir.createDirectory()

    val videoIndex: List[Video] = {

      implicit val s = Scheduler.global
      val scanResult = scanPath(libraryDir.path, config.indexPath, config.scanParallelFactor, config.max)
      scanResult
    }

    val collections: List[Collection] = {

      val dirs = videoIndex.foldLeft(Set.empty[String]) {
        case (set, e) =>
          val parent = (libraryDir / e.fileName).parent
          val relative = s"/${libraryDir.relativize(parent)}"
          set + relative
      }

      dirs.toList.sorted.zipWithIndex.map {
        case (e, idx) => Collection(idx, e)
      }
    }

    (videoIndex, collections)
  }
}


