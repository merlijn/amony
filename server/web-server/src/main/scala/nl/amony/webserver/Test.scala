package nl.amony.webserver

import cats.effect.{IO, IOApp}
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.VideoStream
import nl.amony.search.solr.TarGzExtractor

import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object Test extends IOApp.Simple {

  def run: IO[Unit] = {

    IO {
      val cachePath = Path.of("/Volumes/Data/.amony/cache")
      val hash = "jtvv4ljrwso32hhwyml4dv3t"

      val ts = cachePath.toFile.list((dir: File, name: String) => name.startsWith(hash) && name.endsWith(".webp")).toList.map {
        case name@s"${hash}_${timestamp}_${quality}.webp" =>
          val path = cachePath.resolve(name)
          val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
          timestamp.toLong -> attrs.creationTime().toMillis
      }.maxByOption(_._2).map(_._1)

      println(ts)
    }
  }
}
