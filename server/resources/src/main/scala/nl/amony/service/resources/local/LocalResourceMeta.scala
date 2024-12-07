package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources._
import nl.amony.service.resources.api.{ImageMeta, ResourceMeta, VideoMeta}
import scribe.Logging

import java.nio.file.Path

object LocalResourceMeta extends Logging {

  def resolveMeta(path: Path): IO[Option[ResourceMeta]] = {
    Resource.contentTypeForPath(path) match {

      case None =>
        logger.info(s"No content type found for $path")
        IO.pure(None)

      case Some(contentType) if contentType.startsWith("video") =>
        FFMpeg.ffprobe(path, false).map {
          probe =>
            probe.firstVideoStream.map { stream =>
              VideoMeta(
                width = stream.width,
                height = stream.height,
                durationInMillis = stream.durationMillis,
                fps = stream.fps.toFloat,
                codec = Some(stream.codec_name),
                metaData = Map.empty,
              )
            }
        }
      case Some(contentType) if contentType.startsWith("image") =>
        ImageMagick.getImageMeta(path).map(out =>
          out.headOption.map { meta =>
            ImageMeta(
              width = meta.image.geometry.width,
              height = meta.image.geometry.height,
              metaData = meta.image.properties,
            )
          }
        )
      case Some(contentType) => IO.pure(Some(ResourceMeta.Empty))
    }
  }
}
