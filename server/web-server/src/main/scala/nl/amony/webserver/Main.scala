package nl.amony.webserver

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.toSemigroupKOps
import nl.amony.lib.eventbus.EventTopic
import nl.amony.search.SearchRoutes
import nl.amony.search.solr.SolrIndex
import nl.amony.service.auth.{AuthConfig, AuthRoutes, AuthServiceImpl}
import nl.amony.service.resources.ResourceConfig
import nl.amony.service.resources.api.events.ResourceEvent
import nl.amony.service.resources.database.ResourceDatabase
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.ResourceRoutes
import nl.amony.webserver.routes.{AdminRoutes, WebAppRoutes}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import scala.reflect.ClassTag

object Main extends ResourceApp.Forever with ConfigLoader with Logging {

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global
    import scala.concurrent.ExecutionContext.Implicits.global

    logger.info("Starting application, app home directory: " + appConfig.amonyHome)
    logger.debug("Configuration: " + appConfig)

    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)

    for {
      searchService    <- SolrIndex.resource(appConfig.solr)
      authService = new AuthServiceImpl(loadConfig[AuthConfig]("amony.auth"))
      resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
      _ = resourceEventTopic.followTail(searchService.processEvent)
      resourceDatabase <- ResourceDatabase.resource[HsqldbProfile](databaseConfig)
      resourceBuckets = appConfig.resources.map {
        case localConfig : ResourceConfig.LocalDirectoryConfig =>
          val bucket = new LocalDirectoryBucket(localConfig, resourceDatabase, resourceEventTopic)
          bucket.sync().unsafeRunAsync(_ => ())
          localConfig.id -> bucket
      }.toMap
      routes =
        ResourceRoutes.apply(resourceBuckets) <+>
          SearchRoutes.apply(searchService, appConfig.search) <+>
          AuthRoutes.apply(authService) <+>
          AdminRoutes.apply(searchService, resourceBuckets) <+>
          WebAppRoutes.apply(appConfig.api)
      _ <- WebServer.run(appConfig.api, routes)
    } yield ()
  }
}
