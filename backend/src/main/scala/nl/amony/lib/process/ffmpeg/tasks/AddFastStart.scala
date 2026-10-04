package nl.amony.lib.process.ffmpeg.tasks

import java.nio.file.Path

import cats.effect.IO

import nl.amony.lib.files.*
import nl.amony.lib.process.{Command, ProcessRunner}
import nl.amony.modules.resources.api.VideoContainer

trait AddFastStart:

  self: ProcessRunner =>

  def addFastStart(video: Path, container: VideoContainer): IO[Path] =

    val out = s"${video.stripExtension()}-faststart.${container.extension}"

    // MP4 faststart moves the moov atom to the front, Matroska/WebM moves the Cues (its index) to the front.
    val containerArgs = container match
      case VideoContainer.Mp4      => List("-f", "mp4", "-movflags", "+faststart")
      case VideoContainer.Webm     => List("-f", "webm", "-cues_to_front", "1")
      case VideoContainer.Matroska => List("-f", "matroska", "-cues_to_front", "1")

    val args = List("-v", "error", "-i", video.absoluteFileName(), "-c", "copy", "-map", "0") ++ containerArgs ++ List("-y", out)

    runIgnoreOutput("ffmpeg-add-faststart", Command("ffmpeg", args)).map(_ => Path.of(out))
