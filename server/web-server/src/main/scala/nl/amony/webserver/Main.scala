package nl.amony.webserver

import com.typesafe.config.{Config, ConfigFactory}
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.lib.eventbus.{EventTopic, EventTopicKey, PersistenceCodec}
import nl.amony.search.InMemorySearchService
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.api.events.{MediaAdded, MediaEvent}
import nl.amony.service.media.tasks.MediaScanner
import nl.amony.service.media.{MediaService, MediaStorage}
import nl.amony.service.resources.events.{ResourceEvent, ResourceEventMessage}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalDirectoryStorage}
import pureconfig.{ConfigReader, ConfigSource}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag
import scala.util.Try

object Main extends ConfigLoader with Logging {

  def h2Config(dbPath: Path): DatabaseConfig[HsqldbProfile] = {

    val profile = "slick.jdbc.HsqldbProfile$"

    val config =
      s"""
        |hsqldb-test = {
        |  db {
        |    url = "jdbc:hsqldb:file:${dbPath}/db;user=SA;password=;shutdown=true;hsqldb.applog=0"
        |    driver = "org.hsqldb.jdbcDriver"
        |  }
        |
        |  connectionPool = disabled
        |  profile = "$profile"
        |  keepAliveConnection = true
        |}
        |""".stripMargin

    DatabaseConfig.forConfig[HsqldbProfile]("hsqldb-test", ConfigFactory.parseString(config))
  }

  def loadConfig[T: ClassTag](config: Config, path: String)(implicit reader: ConfigReader[T]): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }

  def main(args: Array[String]): Unit = {

    import cats.effect.unsafe.implicits.global
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    Files.createDirectories(appConfig.media.resourcePath)

    val dbConfig = h2Config(appConfig.media.getIndexPath().resolve("db"))

    val searchService = new InMemorySearchService()

    val mediaService = {
      val mediaStorage = new MediaStorage(dbConfig)
      Await.result(mediaStorage.createTables(), 5.seconds)
      val topic = EventTopic.transientEventTopic[MediaEvent]
      val service = new MediaService(mediaStorage, topic)

      topic.followTail(searchService.indexEvent _)
      service.getAll().foreach { _.foreach(m => topic.publish(MediaAdded(m))) }
      service
    }

    val authService: AuthService = {
      import pureconfig.generic.auto._
      new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))
    }

    val eventBus = new SlickEventBus(dbConfig)
    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileRepository = new LocalDirectoryStorage(appConfig.media, topic, dbConfig)

    val resourceBuckets = Map(appConfig.media.id -> new LocalDirectoryBucket(appConfig.media, localFileRepository))
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
