package nl.amony.lib.process.magick.tasks

import java.nio.file.Path
import scala.util.Try

import cats.effect.IO

import nl.amony.lib.process.ProcessRunner
import nl.amony.lib.process.magick.{MagickImageMeta, MagickResult}

trait GetImageMetaData {

  self: ProcessRunner =>

  def getImageMeta(path: Path): IO[Try[MagickResult]] = {

    val fileName = path.toAbsolutePath.normalize().toString

    useProcessOutput("convert", List(fileName, "json:"), false) {
      processOutput =>
        IO {
          (for
            json <- io.circe.parser.parse(processOutput)
            out  <- json.as[List[MagickImageMeta]]
          yield MagickResult(out, json)).toTry
        }
    }
  }

}
