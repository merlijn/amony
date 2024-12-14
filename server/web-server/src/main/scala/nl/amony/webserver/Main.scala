package nl.amony.webserver

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.toSemigroupKOps
import nl.amony.lib.eventbus.EventTopic
import nl.amony.search.SearchRoutes
import nl.amony.search.solr.SolrIndex
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthRoutes, AuthServiceImpl}
import nl.amony.service.resources.api.events.ResourceEvent
import nl.amony.service.resources.database.ResourceDatabase
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.ResourceRoutes
import nl.amony.service.resources.{ResourceBucket, ResourceConfig}
import nl.amony.webserver.routes.{AdminRoutes, WebAppRoutes}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import scala.reflect.ClassTag

object Main extends ResourceApp.Forever with ConfigLoader with Logging {
  
  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

    import scala.concurrent.ExecutionContext.Implicits.global

    logger.info(config.toString)
    logger.info("Starting application, app home directory: " + appConfig.amonyHome)

    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)
    val searchService = new SolrIndex(appConfig.solr)
    val authService: AuthService = new AuthServiceImpl(loadConfig[AuthConfig]("amony.auth"))

    val resourceDatabase = new ResourceDatabase(databaseConfig)
    if (databaseConfig.config.getBoolean("createTables"))
      resourceDatabase.createTablesIfNotExists().unsafeRunSync()

    val resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
    resourceEventTopic.followTail(searchService.processEvent)

    val resourceBuckets: Map[String, ResourceBucket] = appConfig.resources.map {
      case localConfig : ResourceConfig.LocalDirectoryConfig =>
        
        val bucket = new LocalDirectoryBucket(localConfig, resourceDatabase, resourceEventTopic)

        bucket.sync().unsafeRunAsync(_ => ())
        localConfig.id -> bucket
    }.toMap

    val routes =
      ResourceRoutes.apply(resourceBuckets) <+>
        SearchRoutes.apply(searchService, appConfig.search) <+>
        AuthRoutes.apply(authService) <+>
        AdminRoutes.apply(searchService, resourceBuckets) <+>
        WebAppRoutes.apply(appConfig.api)

    WebServer.run(appConfig.api, routes)
  }
}
