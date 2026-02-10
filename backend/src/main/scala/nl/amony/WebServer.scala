package nl.amony

import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SNIHostName, SSLContext}
import scala.concurrent.duration.DurationInt

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxFlatMapOps
import cats.syntax.all.toSemigroupKOps
import com.comcast.ip4s.{Host, Port}
import fs2.io.file.{Files, Path}
import fs2.io.net.tls.{TLSContext, TLSParameters}
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Cache-Control`
import org.http4s.metrics.MetricsOps
import org.http4s.otel4s.middleware.metrics.OtelMetrics
import org.http4s.server.middleware.Metrics
import org.http4s.server.{Router, Server}
import org.http4s.{Headers, HttpRoutes, Response, Status}
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}
import org.typelevel.otel4s.metrics.MeterProvider
import scribe.Logging

import nl.amony.WebServer.httpServer
import nl.amony.modules.auth.crypt.PemReader
import nl.amony.modules.resources.http.ResourceDirectives
import nl.amony.{HttpConfig, HttpsConfig, WebServerConfig}

object WebServer extends Logging {

  def run(config: WebServerConfig, apiRoutes: HttpRoutes[IO])(using io: IORuntime, meterProvider: MeterProvider[IO]): Resource[IO, Unit] = {

    val routes = apiRoutes <+> webAppRoutes(config)

    val httpResource = config.http match {
      case Some(httpConfig) if httpConfig.enabled =>

        for
          metricsOps <- OtelMetrics.serverMetricsOps[IO]().toResource
          _           = logger.info(s"Starting HTTP server at ${httpConfig.host}:${httpConfig.port}")
          _          <- httpServer(httpConfig, metricsOps, routes)
        yield ()

      case _ =>
        logger.info("HTTP server is disabled")
        Resource.unit[IO]
    }

    val httpsResource = config.https match {
      case Some(httpsConfig) if httpsConfig.enabled =>
        Resource.eval(IO(logger.info(s"Starting HTTPS server at ${httpsConfig.host}:${httpsConfig.port}"))) >> httpsServer(httpsConfig, routes)
          .map(_ => ())
      case _                                        => Resource.eval(IO(logger.info("HTTPS server is disabled")))
    }

    httpResource >> httpsResource
  }

  private val serverError = Response[IO](Status.InternalServerError).putHeaders(org.http4s.headers.`Content-Length`.zero)

  def httpsServer(httpsConfig: HttpsConfig, routes: HttpRoutes[IO])(using io: IORuntime): Resource[IO, Server] = {
    val httpApp = Router("/" -> routes).orNotFound

    val sslContext = {
      val keyStore = PemReader.loadKeyStore(
        certificateChainFile = java.nio.file.Path.of(httpsConfig.certificateChainPem),
        privateKeyFile       = java.nio.file.Path.of(httpsConfig.privateKeyPem),
        keyPassword          = None
      )

      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, "".toCharArray)
      val managers          = keyManagerFactory.getKeyManagers

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(managers, null, new SecureRandom())
      sslContext
    }

    val tlsContext    = TLSContext.Builder.forAsync[IO].fromSSLContext(sslContext)
    val tlsParameters = TLSParameters(serverNames = Some(List(new SNIHostName(httpsConfig.host))))
    val serverLogger  = Slf4jLogger.getLoggerFromName[IO]("nl.amony.app.WebServer")

    EmberServerBuilder.default[IO].withHost(Host.fromString(httpsConfig.host).get).withPort(Port.fromInt(httpsConfig.port).get).withHttpApp(httpApp)
      .withTLS(tlsContext, tlsParameters).withLogger(serverLogger).withErrorHandler {
        e =>
          logger.warn("Internal server error", e)
          IO(serverError)
      }.build
  }

  def httpServer(httpConfig: HttpConfig, metricOps: MetricsOps[IO], routes: HttpRoutes[IO])(using io: IORuntime): Resource[IO, Server] = {

    val httpApp      = Router("/" -> Metrics(metricOps)(routes)).orNotFound
    val serverLogger = Slf4jLogger.getLoggerFromName[IO]("nl.amony.app.WebServer")

    EmberServerBuilder.default[IO]
      .withHost(Host.fromString(httpConfig.host).get)
      .withPort(Port.fromInt(httpConfig.port).get)
      .withHttpApp(httpApp)
      .withLogger(serverLogger).withErrorHandler {
        e =>
          logger.warn("Internal server error", e)
          IO(serverError)
      }.build
  }

  def webAppRoutes(config: WebServerConfig) = {

    val basePath          = Path.fromNioPath(config.webClientPath)
    val indexFile         = basePath.resolve("index.html")
    val chunkSize         = 32 * 1024
    val assetsCachePeriod = 365.days

    HttpRoutes.of[IO]:
      case req @ GET -> rest =>
        val requestedPath = rest.toString() match
          case "" | "/" => "index.html"
          case other    => other

        val requestedFile = basePath.resolve(requestedPath.stripMargin('/'))

        Files[IO].exists(requestedFile).map {
          case true  => requestedFile
          case false => indexFile
        }.flatMap { file =>
          val cacheControlHeaders =
            // files in the assets folder are suffixed with a hash and can be cached indefinitely
            if requestedPath.startsWith("/assets/") then {
              Headers(`Cache-Control`(`max-age`(assetsCachePeriod)))
            } else Headers.empty

          ResourceDirectives.responseFromFile[IO](req, file, chunkSize, additionalHeaders = cacheControlHeaders)
        }
  }
}
