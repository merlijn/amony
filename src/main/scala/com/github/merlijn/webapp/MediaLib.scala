package com.github.merlijn.webapp

import better.files.File
import com.github.merlijn.webapp.lib.FFMpeg.{Probe, writeThumbnail}
import com.github.merlijn.webapp.Model._
import com.github.merlijn.webapp.lib.{FFMpeg, FileUtil}
import io.circe.syntax._
import io.circe.parser.decode
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}

import java.nio.file.Path

object MediaLib extends Logging {

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Video = {
    logger.info(s"Processing: ${videoPath.toAbsolutePath}")

    val info = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3

    val thumbNail = generateThumbnail(videoPath, indexDir, info.id, timeStamp, false)

    val video = asVideo(baseDir, info, thumbNail)

    video
  }

  def scanParallel(scanPath: Path, indexPath: Path, parallelFactor: Int, max: Int, extensions: List[String] = List("mp4", "webm"))(implicit s: Scheduler): Seq[Video] = {

    val obs = Observable.from(FileUtil.walkDir(scanPath).take(max))
      .filter { vid =>
        val fileName = vid.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanVideo(scanPath, p, indexPath) } }

    val c = Consumer.toList[Video]

    obs.consumeWith(c).runSyncUnsafe()
  }

  protected def readIndex(file: File): Seq[Video] = {
    val json = file.contentAsString

    decode[List[Video]](json) match {
      case Right(index) => index
      case Left(error) => throw error
    }
  }

  protected def writeIndex(index: File, videos: Seq[Video]): Unit = {

    index.createFile()
    val json = videos.asJson.toString()
    index.overwrite(json)
  }

  def generateThumbnail(videoPath: Path, outputDir: Path, id: String, timeStamp: Long, overWrite: Boolean): String = {

    val thumbNail = s"${id}-$timeStamp.jpeg"
    val fullFile = s"${outputDir}/$thumbNail"

    FFMpeg.writeThumbnail(
      inputFile = videoPath.toAbsolutePath.toString,
      time = timeStamp,
      outputFile = Some(fullFile),
      overWrite)

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
      thumbnail = s"/files/thumbnails/$thumbnail.jpeg",
      tags = Seq.empty,
      resolution = s"${info.resolution._1}x${info.resolution._2}"
    )
  }
}

case class MediaLibConfig(
  libraryPath: Path,
  indexPath: Path,
  scanParallelFactor: Int
)

class MediaLib(config: MediaLibConfig) extends Logging {

  import MediaLib._

  val libraryDir = File(config.libraryPath)

  // create the index directory if it does not exist
  private val indexDir = File(Config.library.indexPath)
  if (!indexDir.exists)
    indexDir.createDirectory()

  val videoIndex: Seq[Video] = {

    val indexFile = File(config.indexPath) / "index.json"

    if (!indexFile.exists) {
      implicit val s = Scheduler.global
      val scanResult = scanParallel(libraryDir.path, config.indexPath, config.scanParallelFactor, Config.library.max)
      writeIndex(indexFile, scanResult)
      scanResult
    } else {
      readIndex(indexFile)
    }
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

  def search(q: Option[String], page: Int, size: Int, c: Option[Int]): SearchResult = {

    val col = c match {
      case None     => videoIndex
      case Some(id) =>
        collections.find(_.id == id).map { cid =>
          videoIndex.filter(_.fileName.startsWith(cid.name.substring(1)))
        }.getOrElse(videoIndex)
    }

    val result = q match {
      case Some(query) => col.filter(_.fileName.toLowerCase.contains(query.toLowerCase))
      case None        => col
    }

    val start = (page - 1) * size
    val end = Math.min(result.size, page * size)

    val videos = if (start > result.size) {
      List.empty
    } else {
      result.slice(start, end)
    }

    SearchResult(page, size, result.size, videos)
  }

  def setThumbnailAt(id: String, timestamp: Long): Option[Unit] = {

    videoIndex.find(_.id == id).map { v =>

      val sanitizedTimeStamp = Math.max(0, Math.min(v.duration, timestamp))

      generateThumbnail(libraryDir.path, config.indexPath, v.id, sanitizedTimeStamp, true)
    }
  }

  def getById(id: String): Option[Video] = {
    videoIndex.find(_.id == id)
  }
}
