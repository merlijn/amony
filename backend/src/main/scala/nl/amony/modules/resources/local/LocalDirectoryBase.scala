package nl.amony.modules.resources.local

import java.nio.file.Path

import cats.effect.IO
import org.apache.tika.Tika
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.lib.messagebus.EventTopic
import nl.amony.lib.process.ffmpeg.FFMpeg
import nl.amony.lib.process.magick.ImageMagick
import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceEvent, Streamability, ThumbnailFormats, ThumbnailResolutions}
import nl.amony.modules.resources.dal.ResourceDatabase

trait LocalDirectoryBase(
  val config: LocalDirectoryConfig,
  val parallelFactor: Int,
  val db: ResourceDatabase,
  val topic: EventTopic[ResourceEvent],
  val formats: ThumbnailFormats,
  val resolutions: ThumbnailResolutions
)(using meter: Meter[IO], tracer: Tracer[IO]) {

  val ffmpeg      = new FFMpeg
  val imageMagick = new ImageMagick

  val meta = LocalResourceMetaDataScanner(new Tika(), ffmpeg, imageMagick)

  /** Whether the container can be streamed progressively, or `None` when unknown or not a video. */
  protected def streamableOf(path: Path, contentType: Option[String]): IO[Option[Boolean]] =
    if contentType.exists(_.startsWith("video/")) then
      Streamability.detect(path).map {
        case Streamability.Streamable    => Some(true)
        case Streamability.NotStreamable => Some(false)
        case Streamability.Unknown       => None
      }
    else IO.pure(None)
}
