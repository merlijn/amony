package nl.amony.webserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import nl.amony.lib.config.ConfigHelper
import nl.amony.search.InMemorySearchService
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.{AuthConfig, AuthServiceImpl}
import nl.amony.service.media.tasks.LocalMediaScanner
import nl.amony.service.media.{MediaRepository, MediaService}
import nl.amony.service.resources.local.{LocalDirectoryBucket, LocalResourcesStore, LocalResourcesStoreNew}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.HsqldbProfile

import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def rootBehaviour(config: AmonyConfig, mediaService: MediaService): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

//      DatabaseMigrations.run(context.system)
      val resourceBuckets = Map("local" -> new LocalDirectoryBucket(context.system))

      val _ = context.spawn(LocalResourcesStore.behavior(config.media), "local-files-store")

      logger.info(s"spawning scanner")
      val _ = context.spawn(LocalMediaScanner.behavior(config.media, resourceBuckets, mediaService), "scanner")

      Behaviors.empty
    }

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

    val dbConfig = h2Config(appConfig.media.getIndexPath())

    val mediaService     = {
      val mediaRepository = new MediaRepository(dbConfig)
      Await.result(mediaRepository.createTables(), 5.seconds)
      val service = new MediaService(mediaRepository)
      service
    }

    val searchService = new InMemorySearchService()

    mediaService.setEventListener(e => searchService.update(e))

    val router: Behavior[Nothing]    = rootBehaviour(appConfig, mediaService)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    val authService: AuthService = {
      import pureconfig.generic.auto._
      val config = ConfigHelper.loadConfig[AuthConfig](system.settings.config, "amony.auth")
      new AuthServiceImpl(config)
    }

    val newStore = new LocalResourcesStoreNew(appConfig.media, dbConfig)

//    Thread.sleep(500)

//    adminApi.scanLibrary()(timeout.duration)

//    adminApi.generatePreviewSprites()

//    probeAll(api)(system.executionContext)
//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    val path = appConfig.media.indexPath.resolve("export.json")
//    MigrateMedia.importFromExport(path, mediaApi)(10.seconds)
//    watchPath(appConfig.media.mediaPath)

    val routes = WebServerRoutes(
      system,
      authService,
      mediaService,
      searchService,
      Map("local" -> new LocalDirectoryBucket(system)),
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
