package nl.amony.webserver

import cats.effect.IO
import com.typesafe.config.Config
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.lib.eventbus.{EventTopic, EventTopicKey, PersistenceCodec}
import nl.amony.search.InMemorySearchService
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.api.events.{MediaAdded, MediaEvent}
import nl.amony.service.media.tasks.MediaScanner
import nl.amony.service.media.{MediaServiceImpl, MediaStorage}
import nl.amony.service.resources.api.events.{ResourceEvent, ResourceEventMessage}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalDirectoryScanner}
import nl.amony.service.resources.{ResourceBucket, ResourceConfig}
import pureconfig.{ConfigReader, ConfigSource}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag
import scala.util.Try
import fs2.Stream
import nl.amony.service.resources.local.db.LocalDirectoryDb

object Main extends ConfigLoader with Logging {

  def loadConfig[T: ClassTag](config: Config, path: String)(implicit reader: ConfigReader[T]): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }

  def main(args: Array[String]): Unit = {

    import cats.effect.unsafe.implicits.global
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    logger.info(config.toString)

    val databaseConfig = DatabaseConfig.forConfig[HsqldbProfile]("amony.database", config)

    val searchService = new InMemorySearchService()

    val mediaService = {
      val mediaStorage = new MediaStorage(databaseConfig)
      Await.result(mediaStorage.createTables(), 5.seconds)

      val mediaTopic = EventTopic.transientEventTopic[MediaEvent]
      mediaTopic.followTail(searchService.indexEvent _)

      val service = new MediaServiceImpl(mediaStorage, mediaTopic)

      service.getAll().foreach { _.foreach(m => mediaTopic.publish(MediaAdded(m))) }
      service
    }

    val authService: AuthService = {
      import pureconfig.generic.auto._
      new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))
    }

    val eventBus = new SlickEventBus(databaseConfig)
    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileStorage = new LocalDirectoryDb(databaseConfig)
    localFileStorage.createTablesIfNotExists()

    val resourceBuckets: Map[String, ResourceBucket] = appConfig.resources.map {
      case localConfig : ResourceConfig.LocalDirectoryConfig =>

        val scanner = new LocalDirectoryScanner(localConfig, localFileStorage)

        Stream
          .fixedDelay[IO](5.seconds)
          .evalMap(_ => IO(scanner.sync(topic)))
          .compile.drain.unsafeRunAndForget()

        localConfig.id -> new LocalDirectoryBucket(localConfig, localFileStorage)
    }.toMap

    val scanner = new MediaScanner(resourceBuckets, mediaService)

    topic.processAtLeastOnce("scan-media", 10) { e =>
      scanner.processEvent(e)
    }.compile.drain.unsafeRunAndForget()

    val webServer = new WebServer(appConfig.api)
    val routes = WebServerRoutes.routes(authService, mediaService, searchService, appConfig, resourceBuckets)

    webServer.setup(routes).unsafeRunSync()

    logger.info("Exiting application")

//    scribe.Logger.root
//      .clearHandlers()
//      .clearModifiers()
//      .withHandler(minimumLevel = Some(Level.Debug))
//      .replace()
  }
}
