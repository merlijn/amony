package io.amony

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import io.amony.actor.{MediaLibActor, MediaLibApi}
import io.amony.http.WebServer
import io.amony.lib.{MediaLibScanner, Migration}
import scribe.Logging

import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {
    logger.info("ENV: " + System.getenv().get("ENV"))

    val system: ActorSystem[MediaLibActor.Command] =
      ActorSystem(MediaLibActor(mediaLibConfig), "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    scanLibrary(api, system)

//    Migration.importFromFile(system)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }

  def scanLibrary(api: MediaLibApi, system: ActorSystem[MediaLibActor.Command]) = {
    api
      .getAll()(Timeout(10.seconds))
      .foreach { loadedFromStore =>
        MediaLibScanner.scan(mediaLibConfig, loadedFromStore, system)
      }(system.executionContext)
  }
}
