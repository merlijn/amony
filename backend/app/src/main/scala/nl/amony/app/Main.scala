package nl.amony.app

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.*
import nl.amony.app.routes.{AdminRoutes, WebAppRoutes}
import nl.amony.lib.messagebus.EventTopic
import nl.amony.lib.auth.ApiSecurity
import nl.amony.service.auth.{AuthConfig, AuthRoutes, AuthServiceImpl}
import nl.amony.service.resources.ResourceConfig
import nl.amony.service.resources.api.events.ResourceEvent
import nl.amony.service.resources.database.ResourceDatabase
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.{ResourceContentRoutes, ResourceRoutes}
import nl.amony.service.search.SearchRoutes
import nl.amony.service.search.solr.SolrSearchService
import scribe.{Logger, Logging}
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
      searchService     <- SolrSearchService.resource(appConfig.solr)
      authConfig         = loadConfig[AuthConfig]("amony.auth")
      authService        = new AuthServiceImpl(authConfig)
      resourceEventTopic = EventTopic.transientEventTopic[ResourceEvent]()
      _                  = resourceEventTopic.followTail(searchService.processEvent)
      resourceDatabase  <- ResourceDatabase.make(appConfig.database)
      resourceBuckets   <- appConfig.resources.map {
                               case localConfig : ResourceConfig.LocalDirectoryConfig => 
                                 LocalDirectoryBucket.resource(localConfig, resourceDatabase, resourceEventTopic)
                             }.sequence
      resourceBucketMap  = resourceBuckets.map(b => b.id -> b).toMap
      apiSecurity        = ApiSecurity(authConfig.decoder)
      routes             = ResourceContentRoutes.apply(resourceBucketMap) <+>
                             AuthRoutes.apply(authService, authConfig, apiSecurity) <+>
                             AdminRoutes.apply(searchService, resourceBucketMap, apiSecurity) <+>
                             SearchRoutes.apply(searchService, appConfig.search, apiSecurity) <+>
                             ResourceRoutes.apply(resourceBucketMap, apiSecurity) <+>
                             WebAppRoutes.apply(appConfig.api)
      _                 <- WebServer.run(appConfig.api, routes)
    } yield ()
  }
}
