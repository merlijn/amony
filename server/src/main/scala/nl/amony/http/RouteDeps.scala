package nl.amony.http

import akka.actor.typed.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.lib.MediaLibApi

import scala.concurrent.ExecutionContext

trait RouteDeps {
  val config: WebServerConfig
  val api: MediaLibApi
  implicit val system: ActorSystem[Nothing]

  implicit def materializer: Materializer = Materializer.createMaterializer(system)
  implicit def executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)
}
