package nl.amony.app

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxFlatMapOps
import com.comcast.ip4s.{Host, Hostname, Port}
import fs2.io.net.tls.{TLSContext, TLSParameters}
import nl.amony.app.util.PemReader
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.{Router, Server}
import org.http4s.{HttpRoutes, Response, Status}
import org.typelevel.log4cats.*
import scribe.Logging

import java.nio.file.Path
import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SNIHostName, SNIServerName, SSLContext}
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}

object WebServer extends Logging {

  given slf4jLogger: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run(config: WebServerConfig, routes: HttpRoutes[IO])(implicit io: IORuntime): Resource[IO, Unit] = {
    val httpResource = config.http match {
      case Some(httpConfig) if httpConfig.enabled =>
        logger.info(s"Starting HTTP server at ${httpConfig.host}:${httpConfig.port}")
        httpServer(httpConfig, routes).map(_ => ())
      case _ =>
        logger.info("HTTP server is disabled")
        Resource.unit[IO]
    }

    val httpsResource = config.https match {
      case Some(httpsConfig) if httpsConfig.enabled =>
        logger.info(s"Starting HTTPS server at ${httpsConfig.host}:${httpsConfig.port}")
        httpsServer(httpsConfig, routes).map(_ => ())
      case _ =>
        logger.info("HTTPS server is disabled")
        Resource.unit[IO]
    }

    httpResource >> httpsResource
  }

  private val serverError = Response[IO](Status.InternalServerError).putHeaders(org.http4s.headers.`Content-Length`.zero)
  
  def httpsServer(httpsConfig: HttpsConfig, routes: HttpRoutes[IO])(using io: IORuntime): Resource[IO, Server] = {
    val httpApp = Router("/" -> routes).orNotFound
    

    val sslContext = {
      val keyStore = PemReader.loadKeyStore(
        certificateChainFile = Path.of(httpsConfig.certificateChainPem),
        privateKeyFile = Path.of(httpsConfig.privateKeyPem),
        keyPassword = None
      )

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "".toCharArray)
      val managers = keyManagerFactory.getKeyManagers

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(managers, null, new SecureRandom())
      sslContext
    }

    val tlsContext = TLSContext.Builder.forAsync[IO].fromSSLContext(sslContext)
    val tlsParameters = TLSParameters(serverNames = Some(List(new SNIHostName(httpsConfig.host))))
    val serverLogger = Slf4jLogger.getLoggerFromName[IO]("nl.amony.app.WebServer")

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(httpsConfig.host).get)
      .withPort(Port.fromInt(httpsConfig.port).get)
      .withHttpApp(httpApp)
      .withTLS(tlsContext, tlsParameters)
      .withLogger(serverLogger)
      .withErrorHandler { e =>
        println("Internal server error")
        logger.warn("Internal server error", e)
        IO(serverError)
      }
      .build
  }

  def httpServer(httpConfig: HttpConfig, routes: HttpRoutes[IO])(using io: IORuntime): Resource[IO, Server] = {

    val httpApp = Router("/" -> routes).orNotFound
    val serverLogger = Slf4jLogger.getLoggerFromName[IO]("nl.amony.app.WebServer")

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(httpConfig.host).get)
      .withPort(Port.fromInt(httpConfig.port).get)
      .withHttpApp(httpApp)
      .withLogger(serverLogger)
      .withErrorHandler { e =>
        println("Internal server error")
        logger.warn("Internal server error", e)
        IO(serverError)
      }
      .build
  }
}
