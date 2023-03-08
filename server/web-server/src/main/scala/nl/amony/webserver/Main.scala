package nl.amony.webserver

import com.typesafe.config.{Config, ConfigFactory}
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.lib.eventbus.{EventTopicKey, PersistenceCodec}
import nl.amony.search.{InMemorySearchService, SearchRoutes}
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.tasks.MediaScanner
import nl.amony.service.media.{MediaRepository, MediaService}
import nl.amony.service.resources.events.{ResourceEvent, ResourceEventMessage}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalDirectoryRepository}
import pureconfig.{ConfigReader, ConfigSource}
import scribe.{Level, Logging}
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import java.nio.file.{Files, Path, Paths}
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

    Files.createDirectories(appConfig.media.resourcePath)

    val dbConfig = h2Config(appConfig.media.getIndexPath().resolve("db"))

    val mediaService = {
      val mediaRepository = new MediaRepository(dbConfig)
      Await.result(mediaRepository.createTables(), 5.seconds)
      val service = new MediaService(mediaRepository)
      service
    }

    val searchService = new InMemorySearchService()
    mediaService.setEventListener(e => searchService.update(e))

    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    import cats.effect.unsafe.implicits.global

    val authService: AuthService = {
      import pureconfig.generic.auto._
      new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))
    }

    val eventBus = new SlickEventBus(dbConfig)
    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileRepository = new LocalDirectoryRepository(appConfig.media, topic, dbConfig)

    val resourceBuckets = Map(appConfig.media.id -> new LocalDirectoryBucket(appConfig.media, localFileRepository))
    val scanner = new MediaScanner(resourceBuckets, mediaService)

    topic.processAtLeastOnce("scan-media", 10) { e =>
      scanner.processEvent(e)
    }.compile.drain.unsafeRunAndForget()

//    val routes = WebServerRoutes(
//      authService,
//      mediaService,
//      searchService,
//      resourceBuckets,
//      appConfig
//    )

    val webServer = new WebServer(appConfig.api)
    val routes = WebServerRoutes.routes(mediaService, searchService, appConfig, resourceBuckets)

    webServer.start(routes)

//    scribe.Logger.root
//      .clearHandlers()
//      .clearModifiers()
//      .withHandler(minimumLevel = Some(Level.Debug))
//      .replace()
  }
}
