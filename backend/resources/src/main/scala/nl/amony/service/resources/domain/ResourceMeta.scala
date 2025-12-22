package nl.amony.service.resources.domain

import scala.util.{Failure, Try}

import nl.amony.lib.ffmpeg.tasks.FFProbeModel.{FFProbeOutput, given}
import nl.amony.lib.magick.model.MagickImageMeta

case class ResourceMeta(toolName: String, toolData: String, properties: ContentProperties)

object ResourceMeta:
  def recover(toolName: String, toolData: String): Option[ResourceMeta] =
    ContentProperties(toolName, toolData).toOption.map(p => ResourceMeta(toolName, toolData, p))

sealed trait ContentProperties

case class VideoProperties(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String]         = None,
  metaData: Map[String, String] = Map.empty
) extends ContentProperties

case class ImageProperties(width: Int, height: Int, metaData: Map[String, String] = Map.empty) extends ContentProperties

object ContentProperties:

  def apply(toolName: String, toolData: String): Try[ContentProperties] = toolName match {
    case s if s.startsWith("ffprobe/") =>
      for {
        json    <- io.circe.parser.parse(toolData).toTry
        decoded <- json.as[FFProbeOutput].toTry
        result  <- ffprobeOutputToContentMeta(decoded)
      } yield result

    case s if s.startsWith("magick/") =>
      for {
        json    <- io.circe.parser.parse(toolData).toTry
        decoded <- json.as[List[MagickImageMeta]].toTry
        result  <- magickOutputToContentMeta(decoded)
      } yield result

    case other => Failure(new Exception(s"Unknown tool meta source: $other"))
  }

  def magickOutputToContentMeta(magickResult: List[MagickImageMeta]): Try[ImageProperties] = magickResult.headOption
    .map(magick => ImageProperties(width = magick.image.geometry.width, height = magick.image.geometry.height, metaData = magick.image.properties))
    .toRight(new Exception("No video stream found")).toTry

  def ffprobeOutputToContentMeta(FFProbeOutput: FFProbeOutput): Try[VideoProperties] = FFProbeOutput.firstVideoStream.map {
    stream =>
      VideoProperties(
        width            = stream.width,
        height           = stream.height,
        durationInMillis = stream.durationMillis,
        fps              = stream.fps.toFloat,
        codec            = Some(stream.codec_name),
        metaData         = Map.empty
      )
  }.toRight(new Exception("No video stream found")).toTry
