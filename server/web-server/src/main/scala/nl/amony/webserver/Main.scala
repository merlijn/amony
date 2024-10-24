package nl.amony.webserver

import cats.effect.{ExitCode, IO, IOApp}
import com.typesafe.config.Config
import fs2.Pipe
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.search.InMemorySearchService
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceAdded, ResourceEvent}
import nl.amony.service.resources.local.db.LocalDirectoryDb
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.local.scanner.LocalDirectoryScanner
import nl.amony.service.resources.{ResourceBucket, ResourceConfig}
import pureconfig.{ConfigReader, ConfigSource}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.Try

object Main extends IOApp with ConfigLoader with Logging {

  def loadConfig[T: ClassTag : ConfigReader](config: Config, path: String): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }

  override def run(args: List[String]): IO[ExitCode] = {

    import cats.effect.unsafe.implicits.global

    logger.info(config.toString)
    
    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)
    
    logger.info("Starting application, home directory: " + appConfig.amonyHome)
    
    val searchService = new InMemorySearchService()
    val authService: AuthService = new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))

    val eventBus = new SlickEventBus(databaseConfig)
    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

//    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
//    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileStorage = new LocalDirectoryDb(databaseConfig)
    localFileStorage.createTablesIfNotExists()

    val resourceTopic = EventTopic.transientEventTopic[ResourceEvent]()
    resourceTopic.followTail(searchService.indexEvent _)
    val publish: Pipe[IO, ResourceEvent, ResourceEvent] = _.foreach(e => IO.pure(resourceTopic.publish(e)))

    val resourceBuckets: Map[String, ResourceBucket] = appConfig.resources.map {
      case localConfig : ResourceConfig.LocalDirectoryConfig =>

        val scanner = new LocalDirectoryScanner(localConfig)

        // hack to reindex everything on startup
        localFileStorage.getAll(localConfig.id).unsafeRunSync().foreach {
          resource => resourceTopic.publish(ResourceAdded(resource))
        }

        val updateDb: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (localFileStorage.applyEvent(localConfig.id))
        val debug: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (e => IO(logger.info(s"Resource event: $e")))

        logger.info(s"Starting scanner for ${localConfig.resourcePath.toAbsolutePath}")

        val pollInterval = 60.seconds

        def stateFromStorage(): Set[ResourceInfo] = localFileStorage.getAll(localConfig.id).map(_.toSet).unsafeRunSync()

        def pullRetry(s: Set[ResourceInfo]): fs2.Stream[IO, ResourceEvent] =
          scanner.pollingResourceEventStream(stateFromStorage(), pollInterval).handleErrorWith { e =>
            logger.error(s"Scanner failed for ${localConfig.resourcePath.toAbsolutePath}, retrying in $pollInterval", e)
            fs2.Stream.sleep[IO](pollInterval) >> pullRetry(stateFromStorage())
          }

        pullRetry(stateFromStorage())
          .through(debug)
          .through(updateDb)
          .through(publish)
          .compile
          .drain
          .unsafeRunAsync(_ => ())

        localConfig.id -> new LocalDirectoryBucket(localConfig, localFileStorage, resourceTopic)
    }.toMap

    val webServer = new WebServer(appConfig.api)
    val routes = WebServerRoutes.routes(authService, searchService, appConfig, resourceBuckets)

    webServer.run(routes).onCancel(IO(logger.info("Exiting application")))

//    scribe.Logger.root
//      .clearHandlers()
//      .clearModifiers()
//      .withHandler(minimumLevel = Some(Level.Debug))
//      .replace()
  }
}
