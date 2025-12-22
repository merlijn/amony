package nl.amony.service.resources.local

import java.nio.file.Path

import scala.util.{Failure, Success, Try}

import cats.effect.IO
import org.apache.tika.Tika
import scribe.Logging

import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.*
import nl.amony.service.resources.domain.{ImageMeta, ResourceMeta, ResourceMetaSource}

object LocalResourceMeta extends Logging {

  case class LocalResourceMeta(contentType: String, toolMeta: Option[ResourceMetaSource], meta: ResourceMeta)

  private val tika = new Tika()

  private def contentTypeForPath(path: java.nio.file.Path): Option[String] = Try(tika.detect(path)).toOption

  def detectMetaData(path: Path): IO[Option[LocalResourceMeta]] = {
    contentTypeForPath(path) match {

      case None =>
        logger.warn(s"Failed to detect content type for: $path")
        IO.pure(None)

      case Some(contentType) if contentType.startsWith("video/") =>
        FFMpeg.ffprobe(path, false).map {
          (ffprobeResult, json) =>
            ResourceMeta.ffprobeOutputToContentMeta(ffprobeResult).toOption.map {
              meta =>
                val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
                LocalResourceMeta(contentType, Some(ResourceMetaSource(s"ffprobe/$version", json.noSpaces)), meta)
            }
        }.recover { case e: Throwable => logger.error(s"Failed to get video meta data for $path", e); None }
      case Some(contentType) if contentType.startsWith("image/") =>
        ImageMagick.getImageMeta(path).map:
          case Failure(e)      =>
            logger.error(s"Failed to get image meta data for $path", e)
            None
          case Success(result) =>
            val source = ResourceMetaSource("magick/1", result.rawJson.noSpaces)
            result.output.headOption.map {
              magick =>
                val meta = ImageMeta(width = magick.image.geometry.width, height = magick.image.geometry.height, metaData = magick.image.properties)
                LocalResourceMeta(contentType, Some(source), meta)
            }

      case _ => IO.pure(None)
    }
  }
}
