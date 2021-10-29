package nl.amony.http

import akka.actor.typed.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.TranscodeSettings
import nl.amony.lib.AmonyApi

import scala.concurrent.ExecutionContext

trait RouteDeps extends JsonCodecs {
  val config: WebServerConfig
  val api: AmonyApi
  implicit val system: ActorSystem[Nothing]

  override def transcodingSettings: List[TranscodeSettings] = api.config.previews.transcode

  implicit def materializer: Materializer = Materializer.createMaterializer(system)
  implicit def executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)
}
