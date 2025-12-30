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
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import pureconfig.{ConfigReader, ConfigSource}
import scribe.{Logger, Logging}
import skunk.Session
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.lib.auth.ApiSecurity
import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.admin.AdminRoutes
import nl.amony.modules.auth.*
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.resources.database.ResourceDatabase
import nl.amony.modules.resources.domain.ResourceEvent
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.resources.web.{ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.search.SearchRoutes
import nl.amony.modules.search.solr.SolrSearchService

object App extends ResourceApp.Forever with Logging {

  private lazy val config: Config =
    Option(System.getenv().get("AMONY_CONFIG_FILE")) match
      case Some(fileName) =>
        logger.info(s"Loading configuration from file: $fileName")
        ConfigFactory.parseFile(Path.of(fileName).toFile)
      case None           => ConfigFactory.load()

  def makeDatabasePool(config: DatabaseConfig): Resource[IO, Resource[IO, Session[IO]]] = {
    def runMigrations(): IO[Unit] =
      config.getJdbcConnection.flatMap: connection =>
        IO.fromTry(Using(connection) {
          conn =>
            logger.info("Running database migrations...")
            val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
            val liquibase         = new Liquibase("db/00-changelog.yaml", new ClassLoaderResourceAccessor(), liquibaseDatabase)
            liquibase.update()
        })

    for {
      pool <- Session.pooled[IO](
                host     = config.host,
                port     = config.port,
                user     = config.username,
                max      = config.poolSize,
                database = config.database,
                password = config.password
              )
      _    <- Resource.eval(runMigrations())
    } yield pool
  }

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

    val appConfig: AppConfig = ConfigSource.fromConfig(config).at("amony").loadOrThrow[AppConfig]

    logger.info("Starting application, app home directory: " + appConfig.amonyHome)
    logger.debug("Configuration: " + appConfig)

    // somehow the default (slf4j) logger for http4s is not working, so we explicitly set it here
    val serverLog = {
      val serverLogger = Logger("nl.amony.app.Main.serverLogger")
      Http4sServerOptions.defaultServerLog[IO]
        .copy(logLogicExceptions = true, doLogExceptions = (msg, throwable) => IO(serverLogger.error(msg, throwable)))
    }

    given serverOptions: Http4sServerOptions[IO] = Http4sServerOptions.customiseInterceptors[IO].serverLog(serverLog).options

    for {
      searchService     <- SolrSearchService.resource(appConfig.solr)
      resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
      _                  = resourceEventTopic.followTail(searchService.processEvent)
      databasePool      <- makeDatabasePool(appConfig.database)
      resourceDatabase   = ResourceDatabase(databasePool)
      resourceBuckets   <- appConfig.resourceBuckets.map {
                             case localConfig: ResourceConfig.LocalDirectoryConfig => LocalDirectoryBucket
                                 .resource(localConfig, resourceDatabase, resourceEventTopic)
                           }.sequence
      resourceBucketMap  = resourceBuckets.map(b => b.id -> b).toMap
      httpBackend       <- HttpClientCatsBackend.resource[IO]()
      authService        = AuthService(appConfig.auth, httpBackend)
      apiSecurity        = ApiSecurity(appConfig.auth)
      apiRoutes          = ResourceContentRoutes.apply(resourceBucketMap) <+>
                             AuthEndpointServerLogic.apply(authService, appConfig.auth, apiSecurity) <+>
                             AdminRoutes.apply(searchService, resourceBucketMap, apiSecurity, config) <+>
                             SearchRoutes.apply(searchService, appConfig.search, apiSecurity) <+>
                             ResourceRoutes.apply(resourceBucketMap, apiSecurity)
      _                 <- WebServer.run(appConfig.api, apiRoutes)
    } yield ()
  }
}
