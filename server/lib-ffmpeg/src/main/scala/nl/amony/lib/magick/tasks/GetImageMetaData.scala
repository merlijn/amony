package nl.amony.lib.magick.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.ProcessRunner
import nl.amony.lib.magick.model.{ImageMeta, MagickImageMeta}

import java.nio.file.Path

trait GetImageMetaData {

  self: ProcessRunner =>

  def getImageMeta(path: Path): IO[List[MagickImageMeta]] = {

    val fileName = path.toAbsolutePath.normalize().toString

    val args = List(fileName, "-format", "%wx%h", "json:")

    runWithOutput("convert", args, false) { json =>

      IO {
        io.circe.parser.decode[List[MagickImageMeta]](json) match {
          case Left(error) => throw error
          case Right(out)  => out
        }
      }
    }
  }

}
