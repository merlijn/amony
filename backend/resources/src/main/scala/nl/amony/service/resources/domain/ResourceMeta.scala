package nl.amony.service.resources.domain

import scala.util.{Failure, Try}

import nl.amony.lib.ffmpeg.tasks.FFProbeModel.{FFProbeOutput, given}
import nl.amony.lib.magick.model.MagickImageMeta

case class ResourceMetaSource(toolName: String, toolData: String)

sealed trait ResourceMeta

case class VideoMeta(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String]         = None,
  metaData: Map[String, String] = Map.empty
) extends ResourceMeta

case class ImageMeta(width: Int, height: Int, metaData: Map[String, String] = Map.empty) extends ResourceMeta

object ResourceMeta:
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

  def magickOutputToContentMeta(magickResult: List[MagickImageMeta]): Try[ImageMeta] = magickResult.headOption
    .map(magick => ImageMeta(width = magick.image.geometry.width, height = magick.image.geometry.height, metaData = magick.image.properties))
    .toRight(new Exception("No video stream found")).toTry

  def ffprobeOutputToContentMeta(FFProbeOutput: FFProbeOutput): Try[VideoMeta] = FFProbeOutput.firstVideoStream.map {
    stream =>
      VideoMeta(
        width            = stream.width,
        height           = stream.height,
        durationInMillis = stream.durationMillis,
        fps              = stream.fps.toFloat,
        codec            = Some(stream.codec_name),
        metaData         = Map.empty
      )
  }.toRight(new Exception("No video stream found")).toTry
