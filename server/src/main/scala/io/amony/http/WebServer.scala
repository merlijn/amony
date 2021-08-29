package io.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{RejectionHandler, Route, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import io.amony.http.WebConversions._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.amony.actor.MediaLibActor.{ErrorResponse, InvalidCommand, Media, MediaNotFound}
import io.amony.http.WebModel.FragmentRange
import io.amony.lib.MediaLibApi
import io.circe.syntax._
import scribe.Logging

import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}


case class WebServerConfig(
  hostName: String,
  webClientFiles: String,
  requestTimeout: FiniteDuration,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
)

case class HttpsConfig(
  port: Int,
  privateKeyPem: String,
  certificateChainPem: String
)

case class HttpConfig(
  port: Int
)

class WebServer(override val config: WebServerConfig, override val api: MediaLibApi)(override implicit val system: ActorSystem[Nothing])
    extends Logging
    with Routes {

  def run(): Unit = {

    def addBindingHooks(protocol: String, f: Future[Http.ServerBinding]) =
      f.onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          system.log.info("Server online at {}://{}:{}/", protocol, address.getHostString, address.getPort)
        case Failure(ex) =>
          system.log.error("Failed to bind to endpoint, terminating system", ex)
          system.terminate()
      }

    config.https.foreach { httpsConfig =>

      logger.info("Loading SSL files")

      val keyStore = PemReader.loadKeyStore(
        certificateChainFile = File(httpsConfig.certificateChainPem),
        privateKeyFile = File(httpsConfig.privateKeyPem),
        keyPassword = None
      )

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "".toCharArray)
      val managers = keyManagerFactory.getKeyManagers

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(managers, null, new SecureRandom())

      val httpsConnectionContext = ConnectionContext.httpsServer(sslContext)

      val binding = Http().newServerAt(config.hostName, httpsConfig.port).enableHttps(httpsConnectionContext).bind(allRoutes)

      addBindingHooks("https", binding)
    }

    config.http.foreach { httpConfig =>
      val bindingFuture = Http().newServerAt(config.hostName, httpConfig.port).bind(allRoutes)
      addBindingHooks("http", bindingFuture)
    }
  }
}
