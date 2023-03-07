package nl.amony.webserver

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.IpLiteralSyntax
import com.typesafe.config.{Config, ConfigFactory}
import nl.amony.lib.eventbus.jdbc.SlickEventBus
import nl.amony.lib.eventbus.{EventTopicKey, PersistenceCodec}
import nl.amony.search.{InMemorySearchService, SearchRoutesHttp4s}
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.tasks.LocalMediaScanner
import nl.amony.service.media.web.MediaRoutesHttp4s
import nl.amony.service.media.{MediaRepository, MediaService}
import nl.amony.service.resources.{ResourceBucket, ResourceDirectivesHttp4s, ResourceRoutesHttp4s}
import nl.amony.service.resources.events.{ResourceEvent, ResourceEventMessage}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalDirectoryRepository}
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
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
      new AuthServiceImpl(loadConfig[AuthConfig](config, "amony.auth"))
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
    startHttp4sServer(mediaService, searchService, appConfig, resourceBuckets)

    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.Debug))
      .replace()
//    tapirTest()
  }

  case class Test(foo: String, bar: String)

  def startHttp4sServer(mediaService: MediaService, searchService: SearchService, config: AmonyConfig, resourceBuckets: Map[String, ResourceBucket]) = {

    import org.http4s._
    import org.http4s.dsl.io._

      val webAppRoutes = HttpRoutes.of[IO] {
        case req @ GET -> rest =>
          val filePath = rest.toString() match {
            case "" | "/" => "index.html"
            case other => other
          }

          logger.info(s"request for: $filePath")

          val targetFile = {
            val requestedFile = Paths.get(config.api.webClientPath).resolve(filePath.stripMargin('/'))
            if (Files.exists(requestedFile))
              requestedFile
            else
              Paths.get(config.api.webClientPath).resolve("index.html")
          }

          ResourceDirectivesHttp4s.fromPath[IO](req, fs2.io.file.Path.fromNioPath(targetFile), 32 * 1024)
      }

      val routes =
          webAppRoutes <+>
          MediaRoutesHttp4s.apply(mediaService, config.media.transcode) <+>
          ResourceRoutesHttp4s.apply(resourceBuckets) <+>
          SearchRoutesHttp4s.apply(searchService, config.search, config.media.transcode)


      val httpApp = Router("/" -> routes).orNotFound

      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8087")
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()
  }
}
