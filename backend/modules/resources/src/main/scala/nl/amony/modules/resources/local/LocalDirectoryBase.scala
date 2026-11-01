package nl.amony.modules.resources.local

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceEvent, ThumbnailFormats, ThumbnailResolutions}
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.messagebus.MessageTopic
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessSettings}

trait LocalDirectoryBase(
  val config: LocalDirectoryConfig,
  val parallelFactor: Int,
  val db: ResourceDatabase,
  val topic: MessageTopic[ResourceEvent],
  val formats: ThumbnailFormats,
  val resolutions: ThumbnailResolutions,
  val processSettings: ProcessSettings,
  val processLimiter: ProcessLimiter
)(using meter: Meter[IO], tracer: Tracer[IO]) {

  protected given meterInstance: Meter[IO]   = meter
  protected given tracerInstance: Tracer[IO] = tracer
}
