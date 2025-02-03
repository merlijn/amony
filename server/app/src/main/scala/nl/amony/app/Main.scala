package nl.amony.app

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.*
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
import scribe.{Logger, Logging}
import slick.basic.DatabaseConfig
import slick.jdbc.{H2Profile, HsqldbProfile}
import sttp.tapir.server.http4s.Http4sServerOptions

import scala.reflect.ClassTag

object Main extends ResourceApp.Forever with ConfigLoader with Logging {

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

    import scala.concurrent.ExecutionContext.Implicits.global

    logger.info("Starting application, app home directory: " + appConfig.amonyHome)
    logger.debug("Configuration: " + appConfig)

    // somehow the default (slf4j) logger is not working, so we explicitly set it here
    val serverLog = {
      val serverLogger = Logger("nl.amony.app.Main.serverLogger")
      Http4sServerOptions.defaultServerLog[IO].copy(
        logLogicExceptions = true,
        doLogExceptions = (msg, throwable) => IO { serverLogger.error(msg, throwable) },
      )
    }

    given serverOptions: Http4sServerOptions[IO] = Http4sServerOptions
      .customiseInterceptors[IO]
      .serverLog(serverLog)
      .options

    for {
      searchService     <- SolrIndex.resource(appConfig.solr)
      authConfig         = loadConfig[AuthConfig]("amony.auth")
      authService        = new AuthServiceImpl(authConfig)
      resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
      _                  = resourceEventTopic.followTail(searchService.processEvent)
      resourceDatabase  <- ResourceDatabase.resource(config)
      resourceBuckets   <- appConfig.resources.map {
                               case localConfig : ResourceConfig.LocalDirectoryConfig => 
                                 LocalDirectoryBucket.resource(localConfig, resourceDatabase, resourceEventTopic)
                             }.sequence
      resourceBucketMap  = resourceBuckets.map(b => b.id -> b).toMap
      routes             = ResourceContentRoutes.apply(resourceBucketMap) <+>
                             AuthRoutes.apply(authService, authConfig, authConfig.decoder) <+>
                             AdminRoutes.apply(searchService, resourceBucketMap, authConfig.decoder) <+>
                             SearchRoutes.apply(searchService, appConfig.search, authConfig.decoder) <+>
                             ResourceRoutes.apply(resourceBucketMap, authConfig.decoder) <+>
                             WebAppRoutes.apply(appConfig.api)
      _                 <- WebServer.run(appConfig.api, routes)
    } yield ()
  }
}
