package nl.amony

import java.nio.file.Path
import scala.reflect.ClassTag

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.*
import com.typesafe.config.{Config, ConfigFactory}
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pureconfig.ConfigSource
import scribe.{Logger, Logging}
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.server.tracing.otel4s.Otel4sTracing

import nl.amony.modules.auth.*
import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.config.ConfigRoutes
import nl.amony.modules.resources.ResourceModule
import nl.amony.modules.resources.api.ResourceEvent
import nl.amony.modules.resources.http.BucketRoutes
import nl.amony.modules.resources.local.PreviewGenerator
import nl.amony.modules.search.SearchModule
import nl.amony.platform.database.Database
import nl.amony.platform.messagebus.{MessageTopic, MessageTopicKey, PersistentMessageBus}
import nl.amony.platform.observability.Observability
import nl.amony.platform.tapir.dsl.ServerEndpoints

object App extends ResourceApp.Forever with Logging {

  private lazy val config: Config =
    Option(System.getenv().get("AMONY_CONFIG_FILE")) match
      case Some(fileName) =>
        logger.info(s"Loading configuration from file: $fileName")
        ConfigFactory.parseFile(Path.of(fileName).toFile)
      case None           => ConfigFactory.load()

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

    val appConfig: AppConfig = ConfigSource.fromConfig(config).at("amony").loadOrThrow[AppConfig]

    logger.info("Starting application")
    logger.debug("Configuration: " + appConfig)

    // somehow the default (slf4j) logger for http4s is not working, so we explicitly set it here
    val serverLog = {
      val serverLogger = Logger("nl.amony.app.Main.serverLogger")
//      val accessLogger = Logger("nl.amony.app.Main.accessLogger")
      Http4sServerOptions.defaultServerLog[IO]
        .copy(
          logLogicExceptions = true,
          doLogWhenHandled   = (msg, _) => IO.unit,
          doLogExceptions    = (msg, throwable) => IO(serverLogger.error(msg, throwable))
        )
    }

    def application(
      using meterProvider: MeterProvider[IO],
      tracerProvider: TracerProvider[IO],
      meter: Meter[IO],
      tracer: Tracer[IO]
    ): Resource[IO, Unit] =

      val serverOptions = Http4sServerOptions.customiseInterceptors[IO]
        .prependInterceptor(Otel4sTracing(tracer))
        .serverLog(serverLog).options

      for
        databasePool      <- Database.makePool(appConfig.database)
        httpClientBackend <- HttpClientCatsBackend.resource[IO]()
        searchModule      <- SearchModule.resource(appConfig.search)
        eventTopic         = {
          given MessageTopicKey[ResourceEvent] = MessageTopicKey("resource-events")
          PersistentMessageBus.postgres(databasePool, appConfig.messageBus).getTopic[ResourceEvent]
        }
        _                 <- eventTopic.processAtLeastOnce("solr-indexer")(searchModule.indexer.processEvent).compile.drain.background
        resourceModule    <- ResourceModule.resource(appConfig.resources, databasePool, eventTopic)
        previewGenerator   = PreviewGenerator(resourceModule.bucketRegistry)
        _                 <- eventTopic.processAtLeastOnce("preview-generator")(previewGenerator.processEvent).compile.drain.background
        authModule         = AuthModule(appConfig.auth, httpClientBackend, databasePool)
        apiRoutes          = {
          given ApiSecurity = authModule.apiSecurity

          val tapirEndpoints: ServerEndpoints[IO] =
            authModule.routes ++
              resourceModule.routes ++
              BucketRoutes.apply(resourceModule.bucketRegistry, searchModule.resourceIndex) ++
              searchModule.routes(resourceModule.bucketRegistry) ++
              ConfigRoutes.apply(
                resourceModule.thumbResolutions,
                resourceModule.thumbFormats,
                appConfig.resources.previews.supportedVideoFormats,
                appConfig.resources.previews.resolutionPickingStrategy
              )

          resourceModule.contentRoutes <+> Http4sServerInterpreter[IO](serverOptions).toRoutes(tapirEndpoints)
        }
        _                 <- WebServer.run(appConfig.api, apiRoutes, authModule.apiSecurity)
      yield ()

    for
      (meterProvider, tracerProvider, _) <- Observability.resource[IO](appConfig.observability)
      meter                              <- Resource.eval(meterProvider.get("app.amony"))
      tracer                             <- Resource.eval(tracerProvider.get("app.amony"))
      _                                  <- application(using meterProvider, tracerProvider, meter, tracer)
    yield ()
  }
}
