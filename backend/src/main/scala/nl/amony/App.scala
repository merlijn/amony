package nl.amony

import java.nio.file.Path
import scala.reflect.ClassTag
import scala.util.Using

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.*
import com.typesafe.config.{Config, ConfigFactory}
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource
import scribe.{Logger, Logging}
import skunk.Session
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.tracing.otel4s.Otel4sTracing

import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.admin.AdminRoutes
import nl.amony.modules.auth.*
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.resources.api.ResourceEvent
import nl.amony.modules.resources.http.{ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.search.http.SearchRoutes
import nl.amony.modules.search.solr.SolrSearchService

object App extends ResourceApp.Forever with Logging {

  private lazy val config: Config =
    Option(System.getenv().get("AMONY_CONFIG_FILE")) match
      case Some(fileName) =>
        logger.info(s"Loading configuration from file: $fileName")
        ConfigFactory.parseFile(Path.of(fileName).toFile)
      case None           => ConfigFactory.load()

  def runDatabaseMigrations(config: DatabaseConfig): IO[Unit] =
    config.getJdbcConnection.flatMap: connection =>
      IO.fromTry(Using(connection) {
        conn =>
          logger.info("Running database migrations...")
          val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
          val liquibase         = new Liquibase("db/00-changelog.yaml", new ClassLoaderResourceAccessor(), liquibaseDatabase)
          liquibase.update()
      })

  def makeDatabasePool(config: DatabaseConfig)(using tracer: Tracer[IO]): Resource[IO, Resource[IO, Session[IO]]] = {
    for
      pool <- Session.Builder[IO]
                .withHost(config.host)
                .withPort(config.port)
                .withUserAndPassword(config.username, config.password)
                .withDatabase(config.database)
                .pooled(config.poolSize)
      _    <- Resource.eval(runDatabaseMigrations(config))
    yield pool
  }

  def observability(config: ObservabilityConfig): Resource[IO, (meter: Meter[IO], tracer: Tracer[IO])] = {
    def otelObservability(): Resource[IO, (meter: Meter[IO], tracer: Tracer[IO])] =
      for
        otel           <- OtelJava.autoConfigured[IO]()
        meterProvider  <- Resource.eval(otel.meterProvider.get("app.amony"))
        tracerProvider <- Resource.eval(otel.tracerProvider.get("app.amony"))
      yield (meterProvider, tracerProvider)

    def nooopObservability(): Resource[IO, (meter: Meter[IO], tracer: Tracer[IO])] =
      Resource.pure((Meter.noop[IO], Tracer.noop[IO]))

    if config.otelEnabled then otelObservability() else nooopObservability()
  }

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

    val appConfig: AppConfig = ConfigSource.fromConfig(config).at("amony").loadOrThrow[AppConfig]

    logger.info("Starting application, app home directory: " + appConfig.amonyHome)
    logger.debug("Configuration: " + appConfig)

    // somehow the default (slf4j) logger for http4s is not working, so we explicitly set it here
    val serverLog = {
      val serverLogger = Logger("nl.amony.app.Main.serverLogger")
      val accessLogger = Logger("nl.amony.app.Main.accessLogger")
      Http4sServerOptions.defaultServerLog[IO]
        .copy(
          logLogicExceptions = true,
          doLogWhenHandled = (msg, throwable) => IO(accessLogger.info(msg)),
          doLogExceptions = (msg, throwable) => IO(serverLogger.error(msg, throwable)))
    }

    def application(using tracer: Tracer[IO], meter: Meter[IO]): Resource[IO, Unit] = {
      given serverOptions: Http4sServerOptions[IO] =
        Http4sServerOptions.customiseInterceptors[IO]
          .prependInterceptor(Otel4sTracing(tracer))
          .serverLog(serverLog).options

      for
        databasePool      <- makeDatabasePool(appConfig.database)
        httpClientBackend <- HttpClientCatsBackend.resource[IO]()
        resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
        searchService     <- SolrSearchService.resource(appConfig.search.solr)
        _                  = resourceEventTopic.followTail(searchService.processEvent)
        resourceBuckets   <- appConfig.resources.buckets.map {
                               case localConfig: ResourceConfig.LocalDirectoryConfig =>
                                 LocalDirectoryBucket.resource(localConfig, databasePool, resourceEventTopic)
                             }.sequence
        resourceBucketMap  = resourceBuckets.map(b => b.id -> b).toMap
        authModule         = AuthModule(appConfig.auth, httpClientBackend, databasePool)
        apiRoutes          = ResourceContentRoutes.apply(resourceBucketMap, authModule.apiSecurity) <+>
                               authModule.routes <+>
                               AdminRoutes.apply(searchService, resourceBucketMap, authModule.apiSecurity) <+>
                               SearchRoutes.apply(searchService, appConfig.search, authModule.apiSecurity) <+>
                               ResourceRoutes.apply(resourceBucketMap, authModule.apiSecurity)
        _                 <- WebServer.run(appConfig.api, apiRoutes)
      yield ()
    }

    for
      (meter, tracer) <- observability(appConfig.observability)
      _               <- application(using tracer, meter)
    yield ()
  }
}
