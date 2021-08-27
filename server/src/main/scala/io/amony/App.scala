package io.amony

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import io.amony.actor.MediaLibActor
import io.amony.http.WebServer
import io.amony.lib.{MediaLibApi, MediaLibScanner, Migration}
import scribe.Logging

import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

    val system: ActorSystem[MediaLibActor.Command] =
      ActorSystem(MediaLibActor(mediaLibConfig), "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    api.admin.scanLibrary()(10.seconds)

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    Migration.importFromExport(api)(10.seconds)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }
}
