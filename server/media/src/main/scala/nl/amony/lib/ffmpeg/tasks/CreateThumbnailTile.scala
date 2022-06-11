package nl.amony.lib.ffmpeg.tasks

import monix.eval.Task
import nl.amony.lib.ffmpeg.FFMpeg.formatTime
import nl.amony.lib.files.PathOps
import scribe.Logging

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

trait CreateThumbnailTile extends Logging {

  self: ProcessRunner with FFProbe =>

  def calculateNrOfFrames(length: Long): (Int, Int) = {

    // 2, 3,  4,  5,  6,  7,  8,  9,  10,  11,  12
    // 4, 9, 16, 25, 36, 49, 64, 81, 100, 121, 144

    // nr of frames should be at least 16 (4x4)
    val minFrameCount = 16

    // interval between frames should be at least 1 second
    val minInterval = 1000

    // nr of frames should be at most 121 (11x11)
    val maxFrameCount = 144

    // interval between frames should be at most 10 seconds
    val maxInterval = 10 * 1000

    val minByInterval: Long = length / minInterval
    val maxByInterval: Long = length / maxInterval

    val lowerBound = Math.min(minFrameCount, minByInterval)
    val upperBound = Math.min(maxFrameCount, maxByInterval)

    val frames   = Math.max(lowerBound, upperBound)

    val tileSize = Math.ceil(Math.sqrt(frames.toDouble)).toInt

    (tileSize * tileSize) -> tileSize
  }

  def createThumbnailTile(
       inputFile: Path,
       outputDir: Path,
       outputBaseName: Option[String] = None,
       height: Int = 90, // since 16:9 is most common aspect ratio
       overwrite: Boolean = false
  ): Task[Unit] = {

    val fileBaseName = outputBaseName.getOrElse(inputFile.getFileName.stripExtension().toString)

    val vttFilename  = s"$fileBaseName.vtt"
    val webpFilename = s"$fileBaseName.webp"

    if (Files.exists(outputDir.resolve(webpFilename)) && Files.exists(outputDir.resolve(vttFilename)) && !overwrite) {
      logger.info(s"Skipping $inputFile ($webpFilename)")
      Task.unit
    } else {

      ffprobe(inputFile, false, 5.seconds).flatMap { probe =>

        val stream             = probe.firstVideoStream.getOrElse(throw new IllegalStateException("no video stream found"))
        val (frames, tileSize) = calculateNrOfFrames(stream.durationMillis)

        val mod                = ((stream.fps * (stream.durationMillis / 1000)) / frames).toInt

        val width: Int = ((stream.width.toDouble / stream.height) * height).toInt

        // format: off
        val args = List(
          "-i",              inputFile.absoluteFileName(),
          "-filter_complex", s"select='not(mod(n,$mod))',scale=$width:$height,tile=${tileSize}x${tileSize}",
          "-vframes",        "1",
          "-quality",        "80",
          "-y",
          "-an",
          s"$outputDir/$webpFilename"
        )
        // format: on

        runWithOutput[Unit](cmds = "ffmpeg" :: args, useErrorStream = true) { _ =>
          def createWebVtt() = {

            val thumbLength: Int = (stream.durationMillis / frames).toInt
            val builder = new StringBuilder()

            builder.append("WEBVTT\n")

            (0 until frames).foreach { n =>
              val start  = formatTime(thumbLength * n)
              val end    = formatTime(thumbLength * (n + 1))
              val x: Int = n % tileSize
              val y: Int = Math.floor(n / tileSize).toInt

              builder.append(
                s"""
                   |${n + 1}
                   |${start} --> $end
                   |$webpFilename#xywh=${x * width},${y * height},${width},${height}
                   |""".stripMargin
              )
            }

            val content = builder.toString()

            Files.write(outputDir.resolve(vttFilename), content.getBytes(StandardCharsets.UTF_8))
          }

          Task { createWebVtt() }
        }
      }
    }
  }
}
