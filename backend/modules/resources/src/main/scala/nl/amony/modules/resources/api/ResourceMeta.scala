package nl.amony.modules.resources.api

import scala.util.{Failure, Try}

import io.circe.Json

import nl.amony.platform.subprocess.ffmpeg.tasks.FFProbeModel.{FFProbeOutput, given}
import nl.amony.platform.subprocess.magick.MagickImageMeta

case class ToolScan(toolName: String, toolData: Json) derives io.circe.Codec

case class ResourceMeta(properties: Option[ContentProperties], toolScan: Option[ToolScan]) derives io.circe.Codec

object ResourceMeta:

  /** Metadata from a fresh scan of the resource, keeping the raw tool output even when its properties cannot be parsed. */
  def scanned(toolScan: ToolScan): ResourceMeta = ResourceMeta(ContentProperties.from(toolScan).toOption, Some(toolScan))

  /** Metadata projected from the search index, which does not store the raw tool output. */
  def indexed(properties: ContentProperties): ResourceMeta = ResourceMeta(Some(properties), None)

sealed trait ContentProperties derives io.circe.Codec

case class VideoProperties(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String] = None
) extends ContentProperties derives io.circe.Codec

case class ImageProperties(width: Int, height: Int, metaData: Map[String, String] = Map.empty) extends ContentProperties
    derives io.circe.Codec

object ContentProperties:

  def from(toolScan: ToolScan): Try[ContentProperties] = toolScan.toolName match {
    case s if s.startsWith("ffprobe/") =>
      for
        decoded <- toolScan.toolData.as[FFProbeOutput].toTry
        result  <- ffprobeOutputToContentMeta(decoded)
      yield result

    case s if s.startsWith("magick/") =>
      for
        decoded <- toolScan.toolData.as[List[MagickImageMeta]].toTry
        result  <- magickOutputToContentMeta(decoded)
      yield result

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
        codec            = Some(stream.codec_name)
      )
  }.toRight(new Exception("No video stream found")).toTry
