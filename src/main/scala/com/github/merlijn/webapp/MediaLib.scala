package com.github.merlijn.webapp

import better.files.File
import com.github.merlijn.webapp.FFMpeg.Probe
import com.github.merlijn.webapp.Model._
import io.circe.syntax._
import io.circe.parser.decode

class MediaLib(val path: String) extends Logging {

  val extensions = Seq("mp4", "webm")

  def scan(path: String, max: Int): Seq[Probe] = {
    val dir = File(path)
    val matches: Iterator[File] = dir.listRecursively.filter { f =>
      extensions.exists(ext => f.name.endsWith(s".$ext")) && !f.name.startsWith(".")
    }

    matches.take(max).map { f =>

      logger.debug(s"Processing: ${f.path.toAbsolutePath}")

      val info = FFMpeg.ffprobe(f)

      info
    }.toSeq
  }

  val indexFile = File(Config.indexPath) / "index.json"

  val videoIndex: List[Video] =
    if (!indexFile.exists) {
      val scanResult = scan(Config.path, 36)
      indexFile.createFile()
      val index = scanResult.map(asVideo).toList
      val json = index.asJson.toString()
      indexFile.overwrite(json)
      index
    } else {
      val json = indexFile.contentAsString

      decode[List[Video]](json) match {
        case Right(index) => index
        case Left(error)  => throw error
      }
    }

  videoIndex.foreach { i =>

    logger.debug(s"Generating thumbnail for ${i.fileName}")
    FFMpeg.writeThumbnail(
      inputFile  = i.fileName,
      time       = i.duration / 3,
      outputFile = Some(s"${Config.indexPath}/${i.id}.jpeg"))
  }

  def asVideo(info: Probe): Video =
    Video(
      id         = info.id,
      fileName   = info.fileName,
      title      = info.fileName,
      duration   = info.duration,
      thumbnail  = s"/files/thumbnails/${info.id}.jpeg",
      tags       = Seq.empty,
      resolution = s"${info.resolution._1}x${info.resolution._2}"
    )

  def search(query: String): List[Video] = {
    videoIndex.filter(_.title.contains(query))
  }
}
