package nl.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import akka.util.Timeout
import better.files.File
import nl.amony.actor.media.MediaApi
import nl.amony.actor.resources.ResourceApi
import nl.amony.api.AdminApi
import nl.amony.http.routes._
import nl.amony.http.util.PemReader
import nl.amony.user.AuthRoutes
import nl.amony.user.AuthApi
import nl.amony.AmonyConfig
import nl.amony.search.SearchApi
import scribe.Logging

import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

object AllRoutes {

  def createRoutes(
    system: ActorSystem[Nothing],
    userApi: AuthApi,
    mediaApi: MediaApi,
    resourceApi: ResourceApi,
    adminApi: AdminApi,
    config: AmonyConfig
  ): Route = {
    implicit val ec: ExecutionContext = system.executionContext
    import akka.http.scaladsl.server.Directives._

    val searchApi      = new SearchApi(system, Timeout(5.seconds))

    val identityRoutes = AuthRoutes(userApi)
    val resourceRoutes = ResourceRoutes(resourceApi, config.api)
    val searchRoutes   = SearchRoutes(system, searchApi, config.api, config.media.transcode)
    val adminRoutes    = AdminRoutes(adminApi, config.api)
    val mediaRoutes    = MediaRoutes(system, mediaApi, searchApi, config.media.transcode, config.api)

    // routes for the web app (javascript/html) resources
    val webAppResources = WebAppRoutes(config.api)

    val allApiRoutes =
      if (config.api.enableAdmin)
        mediaRoutes ~ adminRoutes
      else
        mediaRoutes

    allApiRoutes ~ searchRoutes ~ identityRoutes ~ resourceRoutes ~ webAppResources
  }
}

class WebServer(val config: WebServerConfig)(implicit val system: ActorSystem[Nothing]) extends Logging {

  def start(route: Route): Unit = {

    implicit val ec: ExecutionContext = system.executionContext

    def addBindingHooks(protocol: String, f: Future[Http.ServerBinding]) =
      f.onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          system.log.info("Server online at {}://{}:{}/", protocol, address.getHostString, address.getPort)
        case Failure(ex) =>
          system.log.error("Failed to bind to endpoint, terminating system", ex)
          system.terminate()
      }

    config.https.filter(_.enabled).foreach { httpsConfig =>
      val keyStore = PemReader.loadKeyStore(
        certificateChainFile = File(httpsConfig.certificateChainPem),
        privateKeyFile       = File(httpsConfig.privateKeyPem),
        keyPassword          = None
      )

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "".toCharArray)
      val managers = keyManagerFactory.getKeyManagers

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(managers, null, new SecureRandom())

      val httpsConnectionContext = ConnectionContext.httpsServer(sslContext)

      val binding =
        Http().newServerAt(config.hostName, httpsConfig.port).enableHttps(httpsConnectionContext).bind(route)

      addBindingHooks("https", binding)
    }

    config.http.filter(_.enabled).foreach { httpConfig =>
      val bindingFuture = Http().newServerAt(config.hostName, httpConfig.port).bind(route)
      addBindingHooks("http", bindingFuture)
    }
  }
}
