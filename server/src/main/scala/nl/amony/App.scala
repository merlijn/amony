package nl.amony

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import nl.amony.actor.MediaLibProtocol
import nl.amony.http.WebServer
import nl.amony.lib.{MediaLibApi, MediaLibScanner, Migration}
import scribe.Logging

import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

    val system: ActorSystem[MediaLibProtocol.Command] =
      ActorSystem(MediaLibProtocol(mediaLibConfig), "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    api.admin.scanLibrary()(10.seconds)

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    Migration.importFromExport(api)(10.seconds)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }
}
