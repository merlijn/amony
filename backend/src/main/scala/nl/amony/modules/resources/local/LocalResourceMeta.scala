package nl.amony.modules.resources.local

import java.nio.file.Path

import scala.util.{Failure, Success, Try}

import cats.effect.IO
import org.apache.tika.Tika
import scribe.Logging

import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.modules.resources.*
import nl.amony.modules.resources.domain.{ContentProperties, ImageProperties, ResourceMeta}

object LocalResourceMeta extends Logging {

  private val tika = new Tika()

  private def contentTypeForPath(path: java.nio.file.Path): Option[String] =
    Try(tika.detect(path)) match
      case Success(contentType) =>
        Some(contentType)
      case Failure(exception)   =>
        logger.warn(s"Error detecting content type for path: $path", exception)
        None

  def apply(path: Path): IO[Option[(contentType: String, meta: ResourceMeta)]] = {
    contentTypeForPath(path) match {

      case None =>
        IO.pure(None)

      case Some(contentType) if contentType.startsWith("video/") =>
        FFMpeg.ffprobe(path, false).map {
          (ffprobeResult, json) =>
            ContentProperties.ffprobeOutputToContentMeta(ffprobeResult).toOption.map {
              properties =>
                val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
                (contentType, ResourceMeta(s"ffprobe/$version", json.noSpaces, properties))
            }
        }.recover { case e: Throwable => logger.error(s"Failed to get video meta data for $path", e); None }
      case Some(contentType) if contentType.startsWith("image/") =>
        ImageMagick.getImageMeta(path).map:
          case Failure(e)      =>
            logger.error(s"Failed to get image meta data for $path", e)
            None
          case Success(result) =>
            result.output.headOption.map {
              magick =>
                val properties =
                  ImageProperties(width = magick.image.geometry.width, height = magick.image.geometry.height, metaData = magick.image.properties)
                (contentType, ResourceMeta("magick/1", result.rawJson.noSpaces, properties))
            }

      case _ => IO.pure(None)
    }
  }
}
