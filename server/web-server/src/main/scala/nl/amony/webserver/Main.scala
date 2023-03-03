package nl.amony.webserver

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import nl.amony.lib.config.ConfigHelper
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.lib.eventbus.{EventTopicKey, PersistenceCodec}
import nl.amony.search.InMemorySearchService
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.tasks.LocalMediaScanner
import nl.amony.service.media.{MediaRepository, MediaService}
import nl.amony.service.resources.events.{ResourceEvent, ResourceEventMessage}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalDirectoryRepository}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
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

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val dbConfig = h2Config(appConfig.media.getIndexPath().resolve("db"))

    val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "mediaLibrary", config)

    val mediaService = {
      val mediaRepository = new MediaRepository(dbConfig)
      Await.result(mediaRepository.createTables(), 5.seconds)
      val service = new MediaService(mediaRepository)
      service
    }

    val searchService = new InMemorySearchService()
    mediaService.setEventListener(e => searchService.update(e))

    implicit val ec: ExecutionContext = system.executionContext
    import cats.effect.unsafe.implicits.global

    val authService: AuthService = {
      import pureconfig.generic.auto._
      new AuthServiceImpl(ConfigHelper.loadConfig[AuthConfig](config, "amony.auth"))
    }

    val eventBus = new SlickEventBus(dbConfig)
    Try { eventBus.createTablesIfNotExists().unsafeRunSync() }

    val codec = PersistenceCodec.scalaPBMappedPersistenceCodec[ResourceEventMessage, ResourceEvent]
    val topic = eventBus.getTopicForKey(EventTopicKey[ResourceEvent]("resource_events")(codec))

    val localFileRepository = new LocalDirectoryRepository(appConfig.media, topic, dbConfig)

    val resourceBuckets = Map(appConfig.media.id -> new LocalDirectoryBucket(appConfig.media, localFileRepository))
    val scanner = new LocalMediaScanner(resourceBuckets, mediaService)

    topic.processAtLeastOnce("scan-media", 10) { e =>
      scanner.processEvent(e)
    }.compile.drain.unsafeRunAndForget()

    val routes = WebServerRoutes(
      authService,
      mediaService,
      searchService,
      resourceBuckets,
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
