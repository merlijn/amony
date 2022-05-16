package nl.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import better.files.File
import nl.amony.actor.media.MediaApi
import nl.amony.actor.resources.ResourceApi
import nl.amony.api.{AdminApi, SearchApi}
import nl.amony.http.routes.{AdminRoutes, ApiRoutes, ResourceRoutes, SearchRoutes}
import nl.amony.http.util.PemReader
import nl.amony.user.{IdentityRoutes, UserApi}
import nl.amony.{AmonyConfig, WebServerConfig}
import scribe.Logging

import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object AllRoutes {

  def createRoutes(system: ActorSystem[Nothing],
                   userApi: UserApi,
                   mediaApi: MediaApi,
                   resourceApi: ResourceApi,
                   adminApi: AdminApi,
                   config: AmonyConfig): Route = {
    implicit val ec: ExecutionContext = system.executionContext

    val identityRoutes = IdentityRoutes.createRoutes(userApi)
    val resourceRoutes = ResourceRoutes.createRoutes(resourceApi, config.api)
    val searchApi   = new SearchApi(system)
    val searchRoutes = SearchRoutes.createRoutes(system, searchApi, config.api, config.media.previews.transcode)
    val adminRoutes = AdminRoutes.createRoutes(adminApi, config.api)

    val apiRoutes = ApiRoutes.createRoutes(system, mediaApi, searchApi, config.media.previews.transcode, config.api)

    import akka.http.scaladsl.server.Directives._

    // routes for the web app (javascript/html) resources
    val webAppResources: Route =
      rawPathPrefix(Slash) {
        extractUnmatchedPath { urlPath =>
          val filePath = urlPath.toString() match {
            case "" | "/" => "index.html"
            case other    => other
          }

          val targetFile = {
            val requestedFile = File(config.api.webClientPath) / filePath
            if (requestedFile.exists)
              requestedFile
            else
              File(config.api.webClientPath) / "index.html"
          }

          getFromFile(targetFile.path.toAbsolutePath.toString)
        }
      }

    val allApiRoutes =
      if (config.api.enableAdmin)
        apiRoutes ~ adminRoutes
      else
        apiRoutes

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
