package nl.amony.app

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.toSemigroupKOps
import nl.amony.app.routes.{AdminRoutes, WebAppRoutes}
import nl.amony.lib.eventbus.EventTopic
import nl.amony.search.SearchRoutes
import nl.amony.search.solr.SolrIndex
import nl.amony.service.auth.{AuthConfig, AuthRoutes, AuthServiceImpl}
import nl.amony.service.resources.ResourceConfig
import nl.amony.service.resources.api.events.ResourceEvent
import nl.amony.service.resources.database.ResourceDatabase
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.{ResourceContentRoutes, ResourceRoutes}
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
      searchService     <- SolrIndex.resource(appConfig.solr)
      authConfig         = loadConfig[AuthConfig]("amony.auth")
      authService        = new AuthServiceImpl(authConfig)
      resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
      _                  = resourceEventTopic.followTail(searchService.processEvent)
      resourceDatabase  <- ResourceDatabase.resource[HsqldbProfile](databaseConfig)
      resourceBuckets    = appConfig.resources.map {
                               case localConfig : ResourceConfig.LocalDirectoryConfig =>
                                 val bucket = new LocalDirectoryBucket(localConfig, resourceDatabase, resourceEventTopic)
                                 bucket.sync().unsafeRunAsync(_ => ())
                                 localConfig.id -> bucket
                             }.toMap
      routes             = ResourceContentRoutes.apply(resourceBuckets) <+>
                             AuthRoutes.apply(authService, authConfig) <+>
                             AuthRoutes.routes(authService, authConfig, authConfig.decoder) <+>
                             AdminRoutes.apply(searchService, resourceBuckets, authConfig.decoder) <+>
                             SearchRoutes.searchResourceRoutes(searchService, appConfig.search, authConfig.decoder) <+>
                             ResourceRoutes.endpointImplementations(resourceBuckets, authConfig.decoder) <+>
                             WebAppRoutes.apply(appConfig.api)
      _                 <- WebServer.run(appConfig.api, routes)
    } yield ()
  }
}
