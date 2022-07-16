package nl.amony.webserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.search.InMemoryIndex
import nl.amony.search.SearchProtocol.QueryMessage
import nl.amony.service.auth.AuthService
import nl.amony.service.media.MediaService
import nl.amony.service.resources.ResourceService
import nl.amony.service.resources.local.{LocalMediaScanner, LocalResourcesStore}
import nl.amony.webserver.admin.AdminApi
import scribe.Logging

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def rootBehaviour(config: AmonyConfig): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

//      DatabaseMigrations.run(context.system)

      val localIndexRef = InMemoryIndex.apply(context)
      val resourceRef   = context.spawn(ResourceService.behavior(config.media), "resources")
      val mediaRef      = context.spawn(MediaService.behavior(config.media, resourceRef), "medialib")
      val userRef       = context.spawn(AuthService.behavior(), "users")

      val _ = context.spawn(LocalMediaScanner.behavior(config.media, mediaRef), "scanner")

      Behaviors.empty
    }

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val router: Behavior[Nothing]    = rootBehaviour(appConfig)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userService  = new AuthService(system)
    val mediaApi     = new MediaService(system)
    val resourcesApi = new ResourceService(system, mediaApi)
    val adminApi     = new AdminApi(mediaApi, resourcesApi, system, appConfig)


    Thread.sleep(500)
    userService.upsertUser(userService.config.adminUsername, userService.config.adminPassword)
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
      mediaApi,
      resourcesApi,
      adminApi,
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
