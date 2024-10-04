package nl.amony.webserver

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.IORuntime
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scribe.Logging
import com.comcast.ip4s.{Host, Port}
import org.slf4j
import org.typelevel.log4cats.*
// assumes dependency on log4cats-slf4j module
import org.typelevel.log4cats.slf4j.Slf4jFactory

class WebServer(val config: WebServerConfig) extends Logging {

  given slf4jLogger: LoggerFactory[IO] = Slf4jFactory.create[IO]

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

  def run(routes: HttpRoutes[IO])(implicit io: IORuntime): IO[ExitCode] = {
    logger.info("Starting web server")

    val httpApp = Router("/" -> routes).orNotFound

    val serverError = Response(Status.InternalServerError).putHeaders(org.http4s.headers.`Content-Length`.zero)

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("0.0.0.0").get)
      .withPort(Port.fromInt(8080).get)
      .withHttpApp(httpApp)
      .withErrorHandler {
        e =>
          logger.warn("Internal server error", e)
          IO(serverError)
      }
//      .withLogger(Slf4jLogger.getLoggerFromSlf4j[IO](slf4jLogger))
      .build
      .use(_ => IO.never)
      .as(ExitCode.Success)
  }
}
