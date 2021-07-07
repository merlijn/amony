package com.github.merlijn.kagera

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.util.Timeout
import better.files.File
import com.github.merlijn.kagera.Model._
import com.github.merlijn.kagera.actor.MediaLibActor
import com.github.merlijn.kagera.actor.MediaLibActor.{AddCollections, AddMedia, GetById, Query, Search, SetThumbnail}
import com.github.merlijn.kagera.lib.FFMpeg.Probe
import com.github.merlijn.kagera.lib.{FFMpeg, FileUtil}
import io.circe.parser.decode
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

object MediaLibApi extends Logging {

  def scanVideo(baseDir: Path, videoPath: Path, indexDir: Path): Video = {
    logger.info(s"Processing: ${videoPath.toAbsolutePath}")

    val info = FFMpeg.ffprobe(videoPath)
    val timeStamp = info.duration / 3

    val thumbNail = generateThumbnail(videoPath, indexDir, info.id, timeStamp)
    val video = asVideo(baseDir, info, thumbNail)

    video
  }

  def scanPath(scanPath: Path, indexPath: Path, parallelFactor: Int, max: Int, extensions: List[String] = List("mp4", "webm"))(implicit s: Scheduler): List[Video] = {

    val obs = Observable.from(FileUtil.walkDir(scanPath).take(max))
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
}

case class MediaLibConfig(
  libraryPath: Path,
  indexPath: Path,
  scanParallelFactor: Int
)

class MediaLibApi(config: MediaLibConfig) extends Logging {

  import MediaLibApi._

  val libraryDir = File(config.libraryPath)

  // create the index directory if it does not exist
  private val indexDir = File(Config.library.indexPath)
  if (!indexDir.exists)
    indexDir.createDirectory()

  private val videoIndex: List[Video] = {

    implicit val s = Scheduler.global
    val scanResult = scanPath(libraryDir.path, config.indexPath, config.scanParallelFactor, Config.library.max)
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

  val system: ActorSystem[MediaLibActor.Command] =
    ActorSystem(MediaLibActor(config), "mediaLibrary", Config.conf)

  system.tell(AddMedia(videoIndex.sortBy(_.fileName)))
  system.tell(AddCollections(collections))

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler
  implicit val timeout: Timeout = 3.seconds

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  def search(q: Option[String], page: Int, size: Int, c: Option[Int]): Future[SearchResult] = {

    system.ask[SearchResult](ref => Search(Query(q, page, size, c), ref))
  }

  def getById(id: String): Option[Video] = {

    val result = system.ask[Option[Video]](ref => GetById(id, ref))
    Await.result(result, timeout.duration)
  }

  def setThumbnailAt(id: String, timestamp: Long): Future[Option[Video]] = {

    system.ask[Option[Video]](ref => SetThumbnail(id, timestamp, ref))
  }
}
