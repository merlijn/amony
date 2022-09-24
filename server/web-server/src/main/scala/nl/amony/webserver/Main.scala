package nl.amony.webserver

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import nl.amony.search.InMemoryIndex
import nl.amony.service.auth.AuthServiceImpl
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.fragments.FragmentService
import nl.amony.service.media.MediaService
import nl.amony.service.media.tasks.LocalMediaScanner
import nl.amony.service.resources.ResourceService
import nl.amony.service.resources.local.LocalResourcesStore
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc
import slick.jdbc.{H2Profile, JdbcProfile}

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def rootBehaviour(config: AmonyConfig, mediaService: MediaService): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

//      DatabaseMigrations.run(context.system)
      // TODO remove this ugly hack
      val localIndexRef: ActorRef = InMemoryIndex.apply(context)
      mediaService.setEventListener((e) => localIndexRef.tell(e, ActorRef.noSender))

      val _      = context.spawn(LocalResourcesStore.behavior(config.media), "local-files-store")
//      val _      = context.spawn(MediaService.behavior(), "medialib")
      val _      = context.spawn(AuthServiceImpl.behavior(), "users")

      logger.info(s"spawning scanner")
      val _ = context.spawn(LocalMediaScanner.behavior(config.media, mediaService), "scanner")

      Behaviors.empty
    }

  def loadDbConfig(): DatabaseConfig[JdbcProfile] = {

    val config =
      """
        |h2mem1-test = {
        |  url = "jdbc:h2:mem:test1"
        |  driver = org.h2.Driver
        |  connectionPool = disabled
        |  keepAliveConnection = true
        |}
        |""".stripMargin

    DatabaseConfig.forConfig[JdbcProfile]("h2mem1-test", ConfigFactory.parseString(config))

//    Database.forConfig("h2mem1-test", ConfigFactory.parseString(config))
  }

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val dbConfig = loadDbConfig()

    val mediaService     = new MediaService(dbConfig)
    mediaService.init()

    val router: Behavior[Nothing]    = rootBehaviour(appConfig, mediaService)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userService: AuthService  = new AuthServiceImpl(system)

    val resourcesService = new ResourceService(system)
    val fragmentService = new FragmentService(system)

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
      userService,
      mediaService,
      fragmentService,
      resourcesService,
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
