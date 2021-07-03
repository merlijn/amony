package com.github.merlijn.webapp

import better.files.File
import com.github.merlijn.webapp.lib.FFMpeg.Probe
import com.github.merlijn.webapp.Model._
import com.github.merlijn.webapp.lib.FFMpeg
import io.circe.syntax._
import io.circe.parser.decode
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}

import java.nio.file.Path

object MediaLib extends Logging {

  def walkDir(dir: Path): Iterable[Path] = {
    import com.github.merlijn.webapp.lib.FileRecurse
    import java.nio.file.Files

    val r = new FileRecurse
    Files.walkFileTree(dir, r)
    r.getFiles()
  }

  def scanFile(dir: File, p: Path): Video = {
    val f = File(p)
    logger.info(s"Processing: ${f.path.toAbsolutePath}")
    val info = FFMpeg.ffprobe(f)
    val video = asVideo(dir, info)
    generateThumbnail(dir, video)
    video
  }

  def scanParallel(dir: File, parallelFactor: Int, max: Int, extensions: List[String] = List("mp4", "webm"))(implicit s: Scheduler): Seq[Video] = {

    val obs = Observable.from(walkDir(dir.path).take(max))
      .filter { p =>
        val fileName = p.getFileName.toString
        extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
      }
      .mapParallelUnordered(parallelFactor) { p => Task { scanFile(dir, p) } }

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

  def generateThumbnail(parent: File, v: Video): Unit = {
    FFMpeg.writeThumbnail(
      inputFile = (parent / v.fileName).path.toAbsolutePath.toString,
      time = v.duration / 3,
      outputFile = Some(s"${Config.library.indexPath}/${v.id}.jpeg"))
  }

  def generateThumbnails(parent: File, videos: Seq[Video]): Unit = {
    videos.foreach { i => generateThumbnail(parent, i) }
  }

  protected def asVideo(parent: File, info: Probe): Video = {

    val relativePath = parent.relativize(File(info.fileName)).toString

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
      thumbnail = s"/files/thumbnails/${info.id}.jpeg",
      tags = Seq.empty,
      resolution = s"${info.resolution._1}x${info.resolution._2}"
    )
  }
}

class MediaLib(val path: String) extends Logging {

  import MediaLib._

  val libraryDir = File(path)

  // create the index directory if it does not exist
  private val indexDir = File(Config.library.indexPath)
  if (!indexDir.exists)
    indexDir.createDirectory()

  val videoIndex: Seq[Video] = {

    val indexFile = File(Config.library.indexPath) / "index.json"

    if (!indexFile.exists) {
      implicit val s = Scheduler.global
      val scanResult = scanParallel(libraryDir, 4, Config.library.max)
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

  def getById(id: String): Option[Video] = {
    videoIndex.find(_.id == id)
  }
}
