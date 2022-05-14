package nl.amony

import akka.actor.typed.{ActorSystem, Behavior}
import akka.util.Timeout
import nl.amony.actor.resources.MediaScanner
import nl.amony.actor.{MainRouter, Message}
import nl.amony.api.{AdminApi, MediaApi, ResourceApi}
import nl.amony.http.{AllRoutes, WebServer}
import nl.amony.user.UserApi
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val scanner                      = new MediaScanner(appConfig)
    val router: Behavior[Message]    = MainRouter.apply(appConfig, scanner)
    val system: ActorSystem[Message] = ActorSystem(router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userApi = new UserApi(system)
    val mediaApi = new MediaApi(system)
    val adminApi = new AdminApi(mediaApi, system, scanner, appConfig)
    val resourcesApi = new ResourceApi(system, mediaApi)

    userApi.upsertUser(appConfig.users.adminUsername, appConfig.users.adminPassword)
    adminApi.scanLibrary()(timeout.duration)
//    probeAll(api)(system.executionContext)
//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)
//    lib.Migration.importFromExport(api)(10.seconds)
//    watchPath(appConfig.media.mediaPath)

    val routes = AllRoutes.createRoutes(
      system, userApi, mediaApi, resourcesApi, adminApi, appConfig
    )

    val webServer = new WebServer(appConfig.api)(system)

    webServer.start(routes)
  }
}