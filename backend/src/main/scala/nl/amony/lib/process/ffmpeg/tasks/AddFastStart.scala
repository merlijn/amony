package nl.amony.lib.process.ffmpeg.tasks

import cats.effect.IO
import nl.amony.lib.files.*
import nl.amony.lib.process.ProcessRunner

import java.nio.file.Path

trait AddFastStart:

  self: ProcessRunner =>
  
  def addFastStart(video: Path): IO[Path] =

    val out = s"${video.stripExtension()}-faststart.mp4"

    useProcessOutput(
      "ffmpeg",
      args = List("-i", video.absoluteFileName(), "-c", "copy", "-map", "0", "-movflags", "+faststart", "-y", out),
      useErrorStream = true
    )(_ => IO(Path.of(out)))

