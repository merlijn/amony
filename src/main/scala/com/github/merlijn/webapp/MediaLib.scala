package com.github.merlijn.webapp

import better.files.File
import com.github.merlijn.webapp.FFMpeg.Probe
import com.github.merlijn.webapp.Model._
import io.circe.syntax._
import io.circe.parser.decode

class MediaLib(val path: String) extends Logging {

  val extensions = Seq("mp4", "webm")

  val libraryPath = File(path)

  def scan(path: String, max: Int): Seq[Probe] = {
    val matches: Iterator[File] = libraryPath.listRecursively.filter { f =>
      extensions.exists(ext => f.name.endsWith(s".$ext")) && !f.name.startsWith(".")
    }

    matches.take(max).map { f =>

      logger.info(s"Processing: ${f.path.toAbsolutePath}")

      val info = FFMpeg.ffprobe(f)

      info
    }.toSeq
  }

  val indexDir = File(Config.library.indexPath)
  val indexFile = File(Config.library.indexPath) / "index.json"

  if (!indexDir.exists)
    indexDir.createDirectory()

  protected def readIndex(): List[Video] = {
    val json = indexFile.contentAsString

    decode[List[Video]](json) match {
      case Right(index) => index
      case Left(error)  => throw error
    }
  }

  protected def writeIndex(): List[Video] = {
    val scanResult = scan(Config.library.path, Config.library.max)
    indexFile.createFile()
    val index = scanResult.map(asVideo).toList
    val json = index.asJson.toString()
    indexFile.overwrite(json)
    index
  }

  val videoIndex: List[Video] = {

    if (!indexFile.exists) {
      writeIndex()
    } else {
      readIndex()
    }
  }

  videoIndex.foreach { i =>

    logger.debug(s"Generating thumbnail for ${i.fileName}")
    FFMpeg.writeThumbnail(
      inputFile  = (libraryPath / i.fileName).path.toAbsolutePath.toString,
      time       = i.duration / 3,
      outputFile = Some(s"${Config.library.indexPath}/${i.id}.jpeg"))
  }

  def search(q: Option[String], page: Int, size: Int): SearchResult = {
    val result = q match {
      case Some(query) => videoIndex.filter(_.fileName.toLowerCase.contains(query.toLowerCase))
      case None        => videoIndex
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

  def asVideo(info: Probe): Video = {

    val relativePath = libraryPath.relativize(File(info.fileName)).toString

    Video(
      id         = info.id,
      fileName   = relativePath,
      title      = relativePath,
      duration   = info.duration,
      thumbnail  = s"/files/thumbnails/${info.id}.jpeg",
      tags       = Seq.empty,
      resolution = s"${info.resolution._1}x${info.resolution._2}"
    )
  }

  def search(query: String): List[Video] = {
    videoIndex.filter(_.title.contains(query))
  }
}
