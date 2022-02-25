package nl.amony

import akka.actor.typed.{ActorSystem, Behavior}
import nl.amony.actor.resources.MediaScanner
import nl.amony.actor.{MainRouter, Message}
import nl.amony.http.WebServer
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

    Files.createDirectories(appConfig.media.resourcePath)

    val scanner                      = new MediaScanner(appConfig)
    val router: Behavior[Message]    = MainRouter.apply(appConfig, scanner)
    val system: ActorSystem[Message] = ActorSystem(router, "mediaLibrary", config)

    val api = new AmonyApi(appConfig, scanner, system)

    api.admin.scanLibrary()(10.seconds)

//    probeAll(api)(system.executionContext)

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    lib.Migration.importFromExport(api)(10.seconds)

    val webServer = new WebServer(appConfig.api, api)(system)

//    watchPath(appConfig.media.mediaPath)

    webServer.run()
  }

  def watchPath(path: Path) = {

    import io.methvin.watcher._
    import io.methvin.watcher.DirectoryChangeEvent.EventType._

    val watcher = DirectoryWatcher.builder.path(path).listener {
      (event: DirectoryChangeEvent) => {
        event.eventType match {
          case CREATE => logger.info(s"File created : ${event.path}")
          case MODIFY => logger.info(s"File modified: ${event.path}")
          case DELETE => logger.info(s"File deleted : ${event.path}")
        }
      }
    }.fileHashing(false).build

    watcher.watchAsync()
  }
}
