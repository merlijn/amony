package nl.amony.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.Http
import better.files.File
import nl.amony.http.routes.{AdminRoutes, ApiRoutes, ResourceRoutes}
import nl.amony.http.util.PemReader
import scribe.Logging
import akka.http.scaladsl.server.Directives._
import nl.amony.user.actor.UserProtocol
import nl.amony.user.{AuthenticationTokenHelper, IdentityRoutes, UserApi}
import nl.amony.{AmonyApi, WebServerConfig}

import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class WebServer(override val config: WebServerConfig, override val api: AmonyApi)(
    override implicit val system: ActorSystem[Nothing]
) extends Logging
    with ApiRoutes
    with ResourceRoutes
    with AdminRoutes
    with UserApi with IdentityRoutes
    with RouteDeps {

  override val tokenHelper: AuthenticationTokenHelper = new AuthenticationTokenHelper(config.jwt)

  val allApiRoutes =
    if (config.enableAdmin)
      apiRoutes ~ adminRoutes
    else
      apiRoutes

  val allRoutes = allApiRoutes ~ identityRoutes ~ resourceRoutes ~ webAppResources

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
        Http().newServerAt(config.hostName, httpsConfig.port).enableHttps(httpsConnectionContext).bind(allRoutes)

      addBindingHooks("https", binding)
    }

    config.http.filter(_.enabled).foreach { httpConfig =>
      val bindingFuture = Http().newServerAt(config.hostName, httpConfig.port).bind(allRoutes)
      addBindingHooks("http", bindingFuture)
    }
  }
}
