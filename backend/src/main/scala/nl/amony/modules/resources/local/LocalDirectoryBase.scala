package nl.amony.modules.resources.local

import cats.effect.IO
import org.apache.tika.Tika
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.lib.messagebus.EventTopic
import nl.amony.lib.process.ffmpeg.FFMpeg
import nl.amony.lib.process.magick.ImageMagick
import nl.amony.modules.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.modules.resources.api.{ResourceEvent, ThumbnailFormats}
import nl.amony.modules.resources.dal.ResourceDatabase

trait LocalDirectoryBase(
  val config: LocalDirectoryConfig,
  val db: ResourceDatabase,
  val topic: EventTopic[ResourceEvent],
  val formats: ThumbnailFormats
)(using meter: Meter[IO], tracer: Tracer[IO]) {

  val ffmpeg      = new FFMpeg
  val imageMagick = new ImageMagick

  val meta = LocalResourceMetaDataScanner(new Tika(), ffmpeg, imageMagick)
}
