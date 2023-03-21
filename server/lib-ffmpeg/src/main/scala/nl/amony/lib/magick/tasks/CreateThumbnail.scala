package nl.amony.lib.magick.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.ProcessRunner
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.files.PathOps

import java.nio.file.Path

trait CreateThumbnail {
  self: ProcessRunner =>

  def createThumbnail(inputFile: Path, outputFile: Option[Path], scaleHeight: Int): IO[Unit] = {

    val input  = inputFile.toAbsolutePath.normalize().toString
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    val args = List(input, "-resize", s"x$scaleHeight", output)

    runIgnoreOutput("magick" :: args, false)
  }

}
