package nl.amony.app

import scala.reflect.ClassTag

import cats.effect.{IO, Resource, ResourceApp}
import cats.implicits.*
import scribe.{Logger, Logging}
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.app.routes.{AdminRoutes, WebAppRoutes}
import nl.amony.lib.auth.ApiSecurity
import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.auth.*
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.resources.database.ResourceDatabase
import nl.amony.modules.resources.domain.ResourceEvent
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.resources.web.{ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.search.SearchRoutes
import nl.amony.modules.search.solr.SolrSearchService

object Main extends ResourceApp.Forever with ConfigLoader with Logging {

  override def run(args: List[String]): Resource[IO, Unit] = {

    import cats.effect.unsafe.implicits.global

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
      resourceDatabase  <- ResourceDatabase.make(appConfig.database)
      resourceBuckets   <- appConfig.resources.map {
                             case localConfig: ResourceConfig.LocalDirectoryConfig => LocalDirectoryBucket
                                 .resource(localConfig, resourceDatabase, resourceEventTopic)
                           }.sequence
      resourceBucketMap  = resourceBuckets.map(b => b.id -> b).toMap
      authConfig         = loadConfig[AuthConfig]("amony.auth")
      httpBackend       <- HttpClientCatsBackend.resource[IO]()
      authService        = AuthService(authConfig, httpBackend)
      apiSecurity        = ApiSecurity(authConfig)
      routes             = ResourceContentRoutes.apply(resourceBucketMap) <+>
                             AuthEndpointServerLogic.apply(authConfig.publicUri, authService, authConfig, apiSecurity) <+>
                             AdminRoutes.apply(searchService, resourceBucketMap, apiSecurity) <+>
                             SearchRoutes.apply(searchService, appConfig.search, apiSecurity) <+>
                             ResourceRoutes.apply(resourceBucketMap, apiSecurity) <+>
                             WebAppRoutes.apply(appConfig.api)
      _                 <- WebServer.run(appConfig.api, routes)
    } yield ()
  }
}
