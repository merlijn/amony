package nl.amony.webserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.search.InMemoryIndex
import nl.amony.search.SearchProtocol.QueryMessage
import nl.amony.service.auth.AuthApi
import nl.amony.service.media.MediaApi
import nl.amony.service.resources.ResourceApi
import nl.amony.service.resources.local.LocalMediaScanner
import nl.amony.webserver.admin.AdminApi
import scribe.Logging

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def rootBehaviour(config: AmonyConfig, scanner: LocalMediaScanner): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      implicit val mat = Materializer(context)

//      DatabaseMigrations.run(context.system)

      val localIndexRef: ActorRef[QueryMessage] = InMemoryIndex.apply(context)
      val resourceRef = context.spawn(ResourceApi.behavior(config.media, scanner), "resources")
      val mediaRef    = context.spawn(MediaApi.behavior(config.media, resourceRef), "medialib")
      val userRef     = context.spawn(AuthApi.behavior(), "users")

      Behaviors.empty
    }

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val scanner                      = new LocalMediaScanner(appConfig.media)
    val router: Behavior[Nothing]    = rootBehaviour(appConfig, scanner)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userApi      = new AuthApi(system)
    val mediaApi     = new MediaApi(system)
    val resourcesApi = new ResourceApi(system, mediaApi)
    val adminApi     = new AdminApi(mediaApi, resourcesApi, system, scanner, appConfig)


    Thread.sleep(500)
    userApi.upsertUser(userApi.config.adminUsername, userApi.config.adminPassword)
    adminApi.scanLibrary()(timeout.duration)

//    adminApi.generatePreviewSprites()

//    probeAll(api)(system.executionContext)
//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    val path = appConfig.media.indexPath.resolve("export.json")
//    MigrateMedia.importFromExport(path, mediaApi)(10.seconds)
//    watchPath(appConfig.media.mediaPath)

    val routes = WebServerRoutes(
      system,
      userApi,
      mediaApi,
      resourcesApi,
      adminApi,
      appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}
