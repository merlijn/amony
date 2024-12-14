package nl.amony.lib.magick.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.tasks.ProcessRunner
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.files.*

import java.nio.file.Path

trait CreateThumbnail {
  self: ProcessRunner =>

  def resizeImage(inputFile: Path, outputFile: Option[Path], width: Option[Int], height: Option[Int]): IO[Int] = {

    val input  = inputFile.toAbsolutePath.normalize().toString
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    val args = List(input, "-resize", s"${width.getOrElse("")}x${height.getOrElse("")}", output)

    runIgnoreOutput("magick", args)
  }
}
