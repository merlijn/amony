package nl.amony.modules.resources.api

import scala.util.{Failure, Try}

import io.circe.Json

import nl.amony.platform.subprocess.ffmpeg.tasks.FFProbeModel.{FFProbeOutput, given}
import nl.amony.platform.subprocess.magick.MagickImageMeta

case class ToolScan(toolName: String, toolData: Json) derives io.circe.Codec

case class ContentMeta(
  contentType: Option[String]           = None,
  streamable: Option[Boolean]           = None,
  properties: Option[ContentProperties] = None,
  toolScan: Option[ToolScan]            = None
) derives io.circe.Codec {

  /** A changed content type replaces the old scan; otherwise a failed scan keeps the properties already known. */
  def updatedWith(fresh: ContentMeta): ContentMeta =
    if fresh.contentType != contentType then fresh
    else
      copy(
        streamable = fresh.streamable.orElse(streamable),
        properties = if fresh.toolScan.isDefined then fresh.properties else properties,
        toolScan   = if fresh.toolScan.isDefined then fresh.toolScan else toolScan
      )
}

object ContentMeta:

  val empty: ContentMeta = ContentMeta()

  /** Metadata from a fresh scan of the resource, keeping the raw tool output even when its properties cannot be parsed. */
  def scanned(toolScan: ToolScan): ContentMeta =
    ContentMeta(properties = ContentProperties.from(toolScan).toOption, toolScan = Some(toolScan))

  /** Metadata projected from the search index, which does not store the raw tool output. */
  def indexed(
    properties: Option[ContentProperties],
    contentType: Option[String] = None,
    streamable: Option[Boolean] = None
  ): ContentMeta = ContentMeta(contentType = contentType, streamable = streamable, properties = properties)

sealed trait ContentProperties derives io.circe.Codec

case class VideoProperties(
  width: Int,
  height: Int,
  fps: Float,
  durationInMillis: Int,
  codec: Option[String]           = None,
  /** Set when the video uses non-square pixels, so output must be scaled to an explicit size. */
  pixelAspectRatio: Option[Float] = None
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
      // ffmpeg autorotates while decoding, so previews must be planned from the displayed
      // dimensions rather than the coded ones.
      VideoProperties(
        width            = stream.displayWidth,
        height           = stream.displayHeight,
        durationInMillis = stream.durationMillis,
        fps              = stream.fps.toFloat,
        codec            = Some(stream.codec_name),
        pixelAspectRatio = stream.pixelAspectRatio.map(_.toFloat)
      )
  }.toRight(new Exception("No video stream found")).toTry
