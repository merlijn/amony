package nl.amony.modules.resources.local

import java.nio.file.Path

import cats.effect.IO
import org.apache.tika.Tika
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.lib.messagebus.EventTopic
import nl.amony.lib.process.ffmpeg.FFMpeg
import nl.amony.lib.process.magick.ImageMagick
import nl.amony.modules.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.modules.resources.api.{ResourceEvent, Streamability, ThumbnailFormats, ThumbnailResolutions}
import nl.amony.modules.resources.dal.ResourceDatabase

trait LocalDirectoryBase(
  val config: LocalDirectoryConfig,
  val db: ResourceDatabase,
  val topic: EventTopic[ResourceEvent],
  val formats: ThumbnailFormats,
  val resolutions: ThumbnailResolutions
)(using meter: Meter[IO], tracer: Tracer[IO]) {

  val ffmpeg      = new FFMpeg
  val imageMagick = new ImageMagick

  val meta = LocalResourceMetaDataScanner(new Tika(), ffmpeg, imageMagick)

  /** The container's streamability, or `None` for non-video content. */
  protected def streamabilityOf(path: Path, contentType: Option[String]): IO[Option[Streamability]] =
    if contentType.exists(_.startsWith("video/")) then Streamability.detect(path).map(Some(_))
    else IO.pure(None)
}
