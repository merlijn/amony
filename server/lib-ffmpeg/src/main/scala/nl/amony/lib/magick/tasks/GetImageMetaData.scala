package nl.amony.lib.magick.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.ProcessRunner
import nl.amony.lib.magick.model.{MagickImageMeta, MagickResult}

import java.nio.file.Path
import scala.util.Try

trait GetImageMetaData {

  self: ProcessRunner =>

  def getImageMeta(path: Path): IO[Try[MagickResult]] = {

    val fileName = path.toAbsolutePath.normalize().toString

    val args = List(fileName, "-format", "json:")

    useProcessOutput("convert", args, false) { processOutput =>
      IO {
        (for {
          json <- io.circe.parser.parse(processOutput)
          out  <- json.as[List[MagickImageMeta]]
        } yield MagickResult(out, json)).toTry
      }
    }
  }

}
