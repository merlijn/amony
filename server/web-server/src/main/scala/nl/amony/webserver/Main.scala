package nl.amony.webserver

import cats.effect.{ExitCode, IO, IOApp}
import com.typesafe.config.Config
import fs2.Pipe
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.search.solr.SolrIndex
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceAdded, ResourceEvent}
import nl.amony.service.resources.local.db.ResourcesDb
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalResourceScanner}
import nl.amony.service.resources.{ResourceBucket, ResourceConfig}
import nl.amony.service.search.api.Query
import pureconfig.{ConfigReader, ConfigSource}
import scribe.{Level, Logging}
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import java.nio.file.Path
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
    import scala.concurrent.ExecutionContext.Implicits.global

    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.Debug))
      .replace()

    logger.info(config.toString)
    
    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)
    
    logger.info("Starting application, home directory: " + appConfig.amonyHome)

    val searchService = new SolrIndex(appConfig.solr)

    val authService: AuthService = new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))

//    val eventBus = new SlickEventBus(databaseConfig)
//    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

//    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
//    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileStorage = new ResourcesDb(databaseConfig)
    localFileStorage.createTablesIfNotExists()

    val resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
    resourceEventTopic.followTail(searchService.processEvent)

    val resourceBuckets: Map[String, ResourceBucket] = appConfig.resources.map {
      case localConfig : ResourceConfig.LocalDirectoryConfig =>

        val indexedResources = searchService.totalDocuments(localConfig.id)
        val dbResources = localFileStorage.count(localConfig.id).unsafeRunSync()

        // hack to reindex everything on startup if the index might be out of sync
        if (indexedResources < dbResources) {
          logger.info(s"Number of indexed documents ($indexedResources) is smaller than the database count ($dbResources)) - Re-indexing all resources.")
          localFileStorage.getAll(localConfig.id).unsafeRunSync().foreach { resource => searchService.processEvent(ResourceAdded(resource)) }
          logger.info(s"Indexing done")
        }

        val updateDb: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (localFileStorage.applyEvent(localConfig.id, e => resourceEventTopic.publish(e)))
        val debug: Pipe[IO, ResourceEvent, ResourceEvent] = _ evalTap (e => IO(logger.info(s"Resource event: $e")))

        logger.info(s"Starting scanner for ${localConfig.resourcePath.toAbsolutePath}")

        def stateFromStorage(): Set[ResourceInfo] = localFileStorage.getAll(localConfig.id).map(_.toSet).unsafeRunSync()

        def pullRetry(s: Set[ResourceInfo]): fs2.Stream[IO, ResourceEvent] =
          LocalResourceScanner.pollingResourceEventStream(stateFromStorage(), localConfig).handleErrorWith { e =>
            logger.error(s"Scanner failed for ${localConfig.resourcePath.toAbsolutePath}, retrying in ${localConfig.pollInterval}", e)
            fs2.Stream.sleep[IO](localConfig.pollInterval) >> pullRetry(stateFromStorage())
          }

        pullRetry(stateFromStorage())
          .through(debug)
          .through(updateDb)
          .compile
          .drain
          .unsafeRunAsync(_ => ())

        localConfig.id -> new LocalDirectoryBucket(localConfig, localFileStorage, resourceEventTopic)
    }.toMap

    val webServer = new WebServer(appConfig.api)
    val routes = WebServerRoutes.routes(authService, searchService, appConfig, resourceBuckets)
    
    webServer.run(routes).onCancel(IO(logger.info("Exiting application")))
  }
}
