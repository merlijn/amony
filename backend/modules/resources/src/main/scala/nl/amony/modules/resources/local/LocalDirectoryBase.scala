package nl.amony.modules.resources.local

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceEvent}
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.messagebus.MessageTopic

trait LocalDirectoryBase(
  val config: LocalDirectoryConfig,
  val parallelFactor: Int,
  val db: ResourceDatabase,
  val topic: MessageTopic[ResourceEvent],
  val ops: FileOperations
)(using meter: Meter[IO], tracer: Tracer[IO]) {

  protected given meterInstance: Meter[IO]   = meter
  protected given tracerInstance: Tracer[IO] = tracer
}
