package com.github.merlijn.webapp

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import better.files.File
import com.github.merlijn.webapp.Model._
import com.github.merlijn.webapp.actor.MediaLibActor
import com.github.merlijn.webapp.actor.MediaLibActor.{GetById, Query, Search, SetThumbnail}
import com.github.merlijn.webapp.lib.FFMpeg.Probe
import com.github.merlijn.webapp.lib.{FFMpeg, FileUtil}
import io.circe.parser.decode
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}

import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object MediaLib extends Logging {

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Video = {
    logger.info(s"Processing: ${videoPath.toAbsolutePath}")

    val info = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3

    val thumbNail = generateThumbnail(videoPath, indexDir, info.id, timeStamp)

    val video = asVideo(baseDir, info, thumbNail)

    video
  }

  def scanParallel(scanPath: Path, indexPath: Path, parallelFactor: Int, max: Int, extensions: List[String] = List("mp4", "webm"))(implicit s: Scheduler): List[Video] = {

    val obs = Observable.from(FileUtil.walkDir(scanPath).take(max))
      .filter { vid =>
        val fileName = vid.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanVideo(scanPath, p, indexPath) } }

    val c = Consumer.toList[Video]

    obs.consumeWith(c).runSyncUnsafe()
  }

  protected def readIndex(file: File): List[Video] = {
    val json = file.contentAsString

    decode[List[Video]](json) match {
      case Right(index) => index
      case Left(error) => throw error
    }
  }

  def writeIndex(index: File, videos: Seq[Video]): Unit = {

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

  private val videoIndex: List[Video] = {

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

  val system: ActorSystem[MediaLibActor.Command] =
    ActorSystem(MediaLibActor(config, videoIndex.sortBy(_.fileName), collections), "videos")

  implicit val scheduler = system.scheduler
  import akka.actor.typed.scaladsl.AskPattern._
  implicit val timeout: Timeout = 3.seconds

  def search(q: Option[String], page: Int, size: Int, c: Option[Int]): SearchResult = {

    val result = system.ask[SearchResult](ref => Search(Query(q, page, size, c), ref))

    Await.result(result, timeout.duration)
  }

  def getById(id: String): Option[Video] = {

    val result = system.ask[Option[Video]](ref => GetById(id, ref))

    Await.result(result, timeout.duration)
  }

  def setThumbnailAt(id: String, timestamp: Long): Option[Video] = {

    val result = system.ask[Option[Video]](ref => SetThumbnail(id, timestamp, ref))

    Await.result(result, timeout.duration)
  }
}
