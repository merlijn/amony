package nl.amony.webserver

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import scribe.Logging

class WebServer(val config: WebServerConfig) extends Logging {

//  def start(route: Route): Unit = {
//
//    implicit val ec: ExecutionContext = system.executionContext
//
//    def addBindingHooks(protocol: String, f: Future[Http.ServerBinding]) =
//      f.onComplete {
//        case Success(binding) =>
//          val address = binding.localAddress
//          logger.info(s"Server online at ${protocol}://${address.getHostString}:${address.getPort}/")
//        case Failure(ex) =>
//          logger.error("Failed to bind to endpoint, terminating system", ex)
//          system.terminate()
//      }
//
//    config.https.filter(_.enabled).foreach { httpsConfig =>
//      val keyStore = PemReader.loadKeyStore(
//        certificateChainFile = Paths.get(httpsConfig.certificateChainPem),
//        privateKeyFile       = Paths.get(httpsConfig.privateKeyPem),
//        keyPassword          = None
//      )
//
//      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
//      keyManagerFactory.init(keyStore, "".toCharArray)
//      val managers = keyManagerFactory.getKeyManagers
//
//      val sslContext: SSLContext = SSLContext.getInstance("TLS")
//      sslContext.init(managers, null, new SecureRandom())
//
//      val httpsConnectionContext = ConnectionContext.httpsServer(sslContext)
//
//      val binding =
//        Http().newServerAt(config.hostName, httpsConfig.port).enableHttps(httpsConnectionContext).bind(route)
//
//      addBindingHooks("https", binding)
//    }
//
//    config.http.filter(_.enabled).foreach { httpConfig =>
//      val bindingFuture = Http().newServerAt(config.hostName, httpConfig.port).bind(route)
//      addBindingHooks("http", bindingFuture)
//    }
//  }

  def start(routes: HttpRoutes[IO])(implicit io: IORuntime) = {
    val httpApp = Router("/" -> routes).orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(httpApp)
      .build
      .allocated
      .unsafeRunSync()
  }
}
