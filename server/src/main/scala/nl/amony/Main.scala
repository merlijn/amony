package nl.amony

import akka.actor.typed.{ActorSystem, Behavior}
import akka.util.Timeout
import nl.amony.actor.MainRouter
import nl.amony.actor.media.MediaApi
import nl.amony.actor.resources.{MediaScanner, ResourceApi}
import nl.amony.api.AdminApi
import nl.amony.http.{AllRoutes, WebServer}
import nl.amony.user.UserApi
import scribe.Logging

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

object Main extends ConfigLoader with Logging {

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val scanner                      = new MediaScanner(appConfig.media)
    val router: Behavior[Nothing]    = MainRouter.apply(appConfig, scanner)
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](router, "mediaLibrary", config)

    implicit val timeout: Timeout = Timeout(10.seconds)

    val userApi = new UserApi(system, appConfig.auth)
    val mediaApi = new MediaApi(system)
    val resourcesApi = new ResourceApi(system, mediaApi)
    val adminApi = new AdminApi(mediaApi, resourcesApi, system, scanner, appConfig)

    userApi.upsertUser(appConfig.auth.adminUsername, appConfig.auth.adminPassword)

    Thread.sleep(200)
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
