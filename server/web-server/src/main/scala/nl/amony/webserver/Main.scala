package nl.amony.webserver

import cats.effect.{ExitCode, IO, IOApp}
import com.typesafe.config.Config
import fs2.Pipe
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.search.SearchRoutes
import nl.amony.search.solr.SolrIndex
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthRoutes, AuthServiceImpl}
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceAdded, ResourceEvent}
import nl.amony.service.resources.local.db.ResourcesDb
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalResourceScanner}
import nl.amony.service.resources.web.ResourceRoutes
import nl.amony.service.resources.{ResourceBucket, ResourceConfig}
import nl.amony.service.search.api.Query
import pureconfig.{ConfigReader, ConfigSource}
import scribe.{Level, Logging}
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile
import org.http4s.*
import org.http4s.dsl.io.*
import cats.implicits.toSemigroupKOps
import nl.amony.webserver.routes.WebAppRoutes

import java.nio.file.Path
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.Try

object Main extends IOApp with ConfigLoader with Logging {
  
  override def run(args: List[String]): IO[ExitCode] = {

    import cats.effect.unsafe.implicits.global
    import scala.concurrent.ExecutionContext.Implicits.global

    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.Debug))
      .replace()

    logger.info(config.toString)
    logger.info("Starting application, home directory: " + appConfig.amonyHome)
    
    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)
    val searchService = new SolrIndex(appConfig.solr)
    val authService: AuthService = new AuthServiceImpl(loadConfig[AuthConfig]("amony.auth"))

//    val eventBus = new SlickEventBus(databaseConfig)
//    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

//    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
//    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val resourceDatabase = new ResourcesDb(databaseConfig)
    resourceDatabase.createTablesIfNotExists()

    val resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
    resourceEventTopic.followTail(searchService.processEvent)

    val resourceBuckets: Map[String, ResourceBucket] = appConfig.resources.map {
      case localConfig : ResourceConfig.LocalDirectoryConfig =>
        
        val indexedResourcesCount = searchService.totalDocuments(localConfig.id)
        val databaseResourcesCount = resourceDatabase.count(localConfig.id).unsafeRunSync().toLong
        
        if (indexedResourcesCount < databaseResourcesCount) {
          logger.info(s"Number of indexed documents ($indexedResourcesCount) is smaller than the database count ($databaseResourcesCount)) - Re-indexing all resources.")
          resourceDatabase.getAll(localConfig.id).unsafeRunSync().foreach { resource => searchService.processEvent(ResourceAdded(resource)) }
          logger.info(s"Indexing done")
        }
        
        val bucket = new LocalDirectoryBucket(localConfig, resourceDatabase, resourceEventTopic)
        bucket.sync().unsafeRunAsync(_ => ())
        localConfig.id -> bucket
    }.toMap

    val routes =
      ResourceRoutes.apply(resourceBuckets) <+>
        SearchRoutes.apply(searchService, appConfig.search) <+>
        AuthRoutes.apply(authService) <+>
        WebAppRoutes.apply(appConfig.api)
    
    WebServer.run(appConfig.api, routes).onCancel(IO(logger.info("Exiting application")))
  }
}
