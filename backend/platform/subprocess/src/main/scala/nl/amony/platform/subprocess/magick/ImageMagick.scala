package nl.amony.platform.subprocess.magick

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.util.Try

import cats.effect.IO

import nl.amony.platform.subprocess.{Command, ProcessRunner}

object ImageMagick {

  private def inputArg(input: URI): String =
    if input.getScheme == "file" then Paths.get(input).toAbsolutePath.normalize.toString
    else input.toString

  def getImageMeta(path: Path)(using runner: ProcessRunner): IO[Try[MagickResult]] =
    getImageMeta(path.toUri)

  def getImageMeta(input: URI)(using runner: ProcessRunner): IO[Try[MagickResult]] = {

    val fileName = inputArg(input)

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
