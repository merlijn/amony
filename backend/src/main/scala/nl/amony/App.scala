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
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import pureconfig.ConfigSource
import scribe.{Logger, Logging}
import skunk.Session
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.server.tracing.otel4s.Otel4sTracing

import nl.amony.lib.messagebus.EventTopic
import nl.amony.lib.observability.Observability
import nl.amony.lib.tapir.dsl.ServerEndpoints
import nl.amony.modules.admin.{AdminRoutes, BucketAdminRoutes}
import nl.amony.modules.auth.*
import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.config.ConfigRoutes
import nl.amony.modules.resources.api.{LocalDirectoryConfig, ResourceBucketConfig, ResourceEvent, ThumbnailFormats, ThumbnailResolutions}
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.resources.http.{CollectionRoutes, ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.resources.{DatabaseBucketRegistry, ResourceConfig}
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
      IO.fromTry(Using(connection) { conn =>
        logger.info("Running database migrations...")
        val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
        val liquibase         = new Liquibase("db/00-changelog.yaml", new ClassLoaderResourceAccessor(), liquibaseDatabase)
        liquibase.update()
      })

  def makeDatabasePool(config: DatabaseConfig)(using tracer: Tracer[IO], meter: Meter[IO]): Resource[IO, Resource[IO, Session[IO]]] = {
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
      meter: Meter[IO],
      tracer: Tracer[IO]
    ): Resource[IO, Unit] = {

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
        thumbResolutions   = ThumbnailResolutions(
                               appConfig.resources.previews.allowedResolutions,
                               appConfig.resources.previews.defaultResolution,
                               appConfig.resources.previews.resolutionStepDown
                             )
        thumbFormats       = ThumbnailFormats(appConfig.resources.previews.supportedImageFormats, appConfig.resources.previews.formatOptions)
        resourceDatabase   = ResourceDatabase(databasePool)
        parallelFactor     = appConfig.resources.parallelFactor
        bucketFactory      = (config: ResourceBucketConfig) =>
                               config match
                                 case localConfig: LocalDirectoryConfig =>
                                   IO {
                                     val bucket = LocalDirectoryBucket(
                                       localConfig,
                                       parallelFactor,
                                       resourceDatabase,
                                       resourceEventTopic,
                                       thumbFormats,
                                       thumbResolutions
                                     )
                                     (bucket, bucket.sync())
                                   }
        bucketRegistry    <- DatabaseBucketRegistry.resource(
                               appConfig.resources.defaultBucket,
                               BucketsDal(databasePool),
                               searchService,
                               bucketFactory
                             )
        authModule         = AuthModule(appConfig.auth, httpClientBackend, databasePool)
        apiRoutes          = {
          given ApiSecurity = authModule.apiSecurity

          val tapirEndpoints: ServerEndpoints[IO] =
            authModule.routes ++
              CollectionRoutes.apply(resourceDatabase, bucketRegistry) ++
              AdminRoutes.apply(searchService, bucketRegistry) ++
              BucketAdminRoutes.apply(bucketRegistry) ++
              SearchRoutes.apply(searchService, appConfig.search, bucketRegistry) ++
              ResourceRoutes.apply(bucketRegistry) ++
              ConfigRoutes.apply(
                thumbResolutions,
                thumbFormats,
                appConfig.resources.previews.supportedVideoFormats,
                appConfig.resources.previews.resolutionPickingStrategy
              )

          ResourceContentRoutes.apply(bucketRegistry, thumbResolutions, thumbFormats) <+>
            Http4sServerInterpreter[IO](serverOptions).toRoutes(tapirEndpoints)
        }
        _                 <- WebServer.run(appConfig.api, apiRoutes, authModule.apiSecurity)
      yield ()
    }

    for
      (meterProvider, tracerProvider, _) <- Observability.resource[IO](appConfig.observability)
      meter                              <- Resource.eval(meterProvider.get("app.amony"))
      tracer                             <- Resource.eval(tracerProvider.get("app.amony"))
      _                                  <- application(using meterProvider, meter, tracer)
    yield ()
  }
}
