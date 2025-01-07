package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.*
import nl.amony.service.resources.api.{ImageMeta, ResourceMeta, ResourceMetaSource, VideoMeta}
import scribe.Logging

import java.nio.file.Path
import scala.util.{Failure, Success}

object LocalResourceMeta extends Logging {

  def resolveMeta(path: Path): IO[Option[(ResourceMetaSource, ResourceMeta)]] = {
    Resource.contentTypeForPath(path) match {

      case None =>
        logger.info(s"No content type found for $path")
        IO.pure(None)

      case Some(contentType) if contentType.startsWith("video") =>
        FFMpeg.ffprobe(path, false).map { (ffprobeResult, json) =>
          ffprobeResult.firstVideoStream.map { stream =>

            val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
            val source  = ResourceMetaSource(s"ffprobe/$version", json.noSpaces)

            val meta = VideoMeta(
              width = stream.width,
              height = stream.height,
              durationInMillis = stream.durationMillis,
              fps = stream.fps.toFloat,
              codec = Some(stream.codec_name),
              metaData = Map.empty
            )

            source -> meta
          }
        }.recover {
          case e: Throwable => logger.error(s"Failed to get video meta for $path", e); None
        }
      case Some(contentType) if contentType.startsWith("image") =>
        ImageMagick.getImageMeta(path).map:
          case Failure(e) =>
            logger.error(s"Failed to get image meta for $path", e)
            None
          case Success(result) =>
            val source = ResourceMetaSource("magick/1", result.rawJson.noSpaces)
            result.output.headOption.map { magick =>
              val meta = ImageMeta(
                width = magick.image.geometry.width,
                height = magick.image.geometry.height,
                metaData = magick.image.properties
              )
              source -> meta
            }
        
      case _ => IO.pure(None)
    }
  }
}
