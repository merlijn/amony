package nl.amony.platform.subprocess.magick

import java.nio.file.Path
import scala.util.Try

import cats.effect.IO

import nl.amony.platform.subprocess.{Command, ProcessRunner}

object ImageMagick {

  def getImageMeta(path: Path)(using runner: ProcessRunner): IO[Try[MagickResult]] = {

    val fileName = path.toAbsolutePath.normalize().toString

    runner.useProcessOutput(
      "get-image-metadata",
      Command.of("convert", fileName, "json:"),
      useErrorStream = false,
      runner.settings.imageMetadataTimeout
    ) {
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
