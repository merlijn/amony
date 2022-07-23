package nl.amony.webserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.search.InMemoryIndex
import nl.amony.service.auth.AuthServiceImpl
import nl.amony.service.auth.api.AuthService.AuthServiceGrpc.AuthService
import nl.amony.service.media.MediaService
import nl.amony.service.resources.ResourceService
import nl.amony.service.resources.local.{LocalMediaScanner, LocalResourcesStore}
import scribe.Logging

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def rootBehaviour(config: AmonyConfig): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

//      DatabaseMigrations.run(context.system)
      val localIndexRef = InMemoryIndex.apply(context)
      val storeRef      = context.spawn(LocalResourcesStore.behavior(config.media), "local-files-store")
      val resourceRef   = context.spawn(ResourceService.behavior(config.media, storeRef), "resources")
      val mediaRef      = context.spawn(MediaService.behavior(config.media.fragments, resourceRef), "medialib")
      val userRef       = context.spawn(AuthServiceImpl.behavior(), "users")

      val _ = context.spawn(LocalMediaScanner.behavior(config.media), "scanner")

      Behaviors.empty
    }

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val router: Behavior[Nothing]    = rootBehaviour(appConfig)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userService: AuthService  = new AuthServiceImpl(system)
    val mediaApi     = new MediaService(system)
    val resourcesApi = new ResourceService(system)

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
      mediaApi,
      resourcesApi,
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
