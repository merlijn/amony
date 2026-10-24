package nl.amony.platform.subprocess.ffmpeg.tasks

import java.nio.file.Path

import cats.effect.IO

import nl.amony.platform.files.*
import nl.amony.platform.subprocess.ffmpeg.ContainerFormat
import nl.amony.platform.subprocess.{Command, ProcessRunner}

trait AddFastStart:

  self: ProcessRunner =>

  def addFastStart(video: Path, format: ContainerFormat, outputFile: Option[Path] = None): IO[Path] =

    val out = outputFile.getOrElse(Path.of(s"${video.stripExtension()}-faststart.${format.extension}"))

    // MP4 faststart moves the moov atom to the front, Matroska/WebM moves the Cues (its index) to the front.
    val containerArgs = format match
      case ContainerFormat.Mp4      => List("-f", format.fmt, "-movflags", "+faststart")
      case ContainerFormat.Webm     => List("-f", format.fmt, "-cues_to_front", "1")
      case ContainerFormat.Matroska => List("-f", format.fmt, "-cues_to_front", "1")

    // Data streams (for example QuickTime timecode tracks) have no codec a target muxer can copy, so drop them.
    val args = List("-v", "error", "-i", video.absoluteFileName(), "-c", "copy", "-map", "0", "-map", "-0:d") ++ containerArgs ++ List(
      "-y",
      out.absoluteFileName()
    )

    runIgnoreOutput("ffmpeg-add-faststart", Command("ffmpeg", args)).map(_ => out)
