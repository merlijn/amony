package nl.amony.webserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.jdbc.db.SlickExtension
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.stream.Materializer
import akka.util.Timeout
import nl.amony.lib.akka.ServiceBehaviors
import nl.amony.search.InMemoryIndex
import nl.amony.search.SearchProtocol.QueryMessage
import nl.amony.service.auth.AuthApi
import nl.amony.service.media.MediaApi
import nl.amony.service.resources.ResourceApi
import nl.amony.service.resources.local.LocalMediaScanner
import nl.amony.webserver.admin.AdminApi
import nl.amony.webserver.database.DatabaseMigrations
import org.flywaydb.core.Flyway
import scribe.Logging

import java.nio.file.{Files, Path}
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

    userApi.upsertUser(userApi.config.adminUsername, userApi.config.adminPassword)

    Thread.sleep(500)
    adminApi.scanLibrary()(timeout.duration)

//    adminApi.generatePreviewSprites()

//    probeAll(api)(system.executionContext)
//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    val path = appConfig.media.indexPath.resolve("export.json")
//    MigrateMedia.importFromExport(path, mediaApi)(10.seconds)
//    watchPath(appConfig.media.mediaPath)

    val routes = AllRoutes.createRoutes(
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
