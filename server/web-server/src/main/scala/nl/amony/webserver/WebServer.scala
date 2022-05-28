package nl.amony.webserver

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.util.Timeout
import nl.amony.search.SearchApi
import nl.amony.service.auth.{AuthApi, AuthRoutes}
import nl.amony.service.media.MediaApi
import nl.amony.service.resources.{ResourceApi, ResourceRoutes}
import nl.amony.webserver.admin.AdminApi
import nl.amony.webserver.routes.{AdminRoutes, MediaRoutes, SearchRoutes, WebAppRoutes}
import scribe.Logging

import java.nio.file.Paths
import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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

    implicit val requestTimeout = Timeout(5.seconds)

    val searchApi      = new SearchApi(system)

    val identityRoutes = AuthRoutes(userApi)
    val resourceRoutes = ResourceRoutes(resourceApi, config.api.uploadSizeLimit.toBytes.toLong)
    val searchRoutes   = SearchRoutes(system, searchApi, config.search, config.media.transcode)
    val adminRoutes    = AdminRoutes(adminApi, config.api)
    val mediaRoutes    = MediaRoutes(system, mediaApi, config.media.transcode, config.api)

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
        certificateChainFile = Paths.get(httpsConfig.certificateChainPem),
        privateKeyFile       = Paths.get(httpsConfig.privateKeyPem),
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
