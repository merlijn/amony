package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.FFProbeOutput
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.*
import nl.amony.service.resources.api.{ImageMeta, ResourceMeta, ResourceMetaSource, VideoMeta}
import org.apache.tika.Tika
import scribe.Logging

import java.nio.file.Path
import scala.util.{Failure, Success, Try}
import nl.amony.lib.ffmpeg.tasks.FFProbeModel.given
import nl.amony.lib.magick.model.{MagickImageMeta, MagickResult}

object LocalResourceMeta extends Logging {

  case class LocalResourceMeta(contentType: String, toolMeta: Option[ResourceMetaSource], meta: ResourceMeta)

  private val tika = new Tika()

  private def contentTypeForPath(path: java.nio.file.Path): Option[String] = Try(tika.detect(path)).toOption
  
  def scanToolMeta(source: ResourceMetaSource): Try[ResourceMeta] = source.toolName match {
    case s if s.startsWith("ffprobe/") =>
      for {
        json    <- io.circe.parser.parse(source.toolData).toTry
        decoded <- json.as[FFProbeOutput].toTry
        result  <- ffprobeOutputToContentMeta(decoded)
      } yield result
        
    case s if s.startsWith("magick/") =>
      for {
        json    <- io.circe.parser.parse(source.toolData).toTry
        decoded <- json.as[List[MagickImageMeta]].toTry
        result  <- magickOutputToContentMeta(decoded)
      } yield result

    case other => Failure(new Exception(s"Unknown tool meta source: $other"))  
  }
  
  private def magickOutputToContentMeta(magickResult: List[MagickImageMeta]): Try[ImageMeta] = 
    magickResult.headOption.map { magick =>
      ImageMeta(
        width = magick.image.geometry.width,
        height = magick.image.geometry.height,
        metaData = magick.image.properties
      )
    }.toRight(new Exception("No video stream found")).toTry
  
  private def ffprobeOutputToContentMeta(FFProbeOutput: FFProbeOutput): Try[VideoMeta] = 
    FFProbeOutput.firstVideoStream.map { stream =>
      VideoMeta(
        width = stream.width,
        height = stream.height,
        durationInMillis = stream.durationMillis,
        fps = stream.fps.toFloat,
        codec = Some(stream.codec_name),
        metaData = Map.empty
      )
    }.toRight(new Exception("No video stream found")).toTry
  
  def detectMetaData(path: Path): IO[Option[LocalResourceMeta]] = {
    contentTypeForPath(path) match {

      case None =>
        logger.warn(s"Failed to detect content type for: $path")
        IO.pure(None)

      case Some(contentType) if contentType.startsWith("video/") =>
        FFMpeg.ffprobe(path, false).map { (ffprobeResult, json) =>
          ffprobeOutputToContentMeta(ffprobeResult).toOption.map { meta =>
            val version = ffprobeResult.program_version.map(_.version).getOrElse("unknown")
            LocalResourceMeta(contentType, Some(ResourceMetaSource(s"ffprobe/$version", json.noSpaces)), meta)
          }
        }.recover {
          case e: Throwable => logger.error(s"Failed to get video meta data for $path", e); None
        }
      case Some(contentType) if contentType.startsWith("image/") =>
        ImageMagick.getImageMeta(path).map:
          case Failure(e) =>
            logger.error(s"Failed to get image meta data for $path", e)
            None
          case Success(result) =>
            val source = ResourceMetaSource("magick/1", result.rawJson.noSpaces)
            result.output.headOption.map { magick =>
              val meta = ImageMeta(
                width    = magick.image.geometry.width,
                height   = magick.image.geometry.height,
                metaData = magick.image.properties
              )
              LocalResourceMeta(contentType, Some(source), meta)
            }
        
      case _ => IO.pure(None)
    }
  }
}
