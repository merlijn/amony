package com.github.merlijn.webapp

import better.files.File
import com.github.merlijn.webapp.lib.FFMpeg.Probe
import com.github.merlijn.webapp.Model._
import com.github.merlijn.webapp.lib.FFMpeg
import io.circe.syntax._
import io.circe.parser.decode

import java.nio.file.Path

object MediaLib extends Logging {

  def walkDir(dir: Path): Iterable[Path] = {
    import com.github.merlijn.webapp.lib.FileRecurse
    import java.nio.file.Files

    val r = new FileRecurse
    Files.walkFileTree(dir, r)
    r.getFiles()
  }

  def scan(dir: File, max: Int, extensions: List[String] = List("mp4", "webm")): Seq[Video] = {

    walkDir(dir.path).filter { p =>
      val fileName = p.getFileName.toString
      extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
    }.take(max).map { p =>
      val f = File(p)
      logger.info(s"Processing: ${f.path.toAbsolutePath}")
      val info = FFMpeg.ffprobe(f)
      val video = asVideo(dir, info)
      generateThumbnail(dir, video)
      video
    }.toSeq
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

    Video(
      id = info.id,
      fileName = relativePath,
      title = relativePath.substring(Math.max(0, relativePath.lastIndexOf('/')), relativePath.lastIndexOf('.')),
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
      val scanResult = scan(libraryDir, Config.library.max)
      writeIndex(indexFile, scanResult)
      scanResult
    } else {
      readIndex(indexFile)
    }
  }

  val collections = List(
    Collection(1, "A"),
    Collection(2, "B"),
    Collection(3, "C"),
  )

  val collectionMap = Map(
    1 -> List("abc", "cdef"),
    2 -> List("foo", "bar"),
    3 -> List("meh", "huh")
  )

  def search(q: Option[String], page: Int, size: Int): SearchResult = {
    val result = q match {
      case Some(query) => videoIndex.filter(_.fileName.toLowerCase.contains(query.toLowerCase))
      case None => videoIndex
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
