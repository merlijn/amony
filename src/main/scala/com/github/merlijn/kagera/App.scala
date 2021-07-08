package com.github.merlijn.kagera

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import com.github.merlijn.kagera.actor.{MediaLibActor, MediaLibApi}
import com.github.merlijn.kagera.actor.MediaLibActor.{AddCollections, AddMedia}
import com.github.merlijn.kagera.http.WebServer
import com.github.merlijn.kagera.lib.MediaLibScanner
import scribe.Logging

import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {
    logger.info("ENV: " + System.getenv().get("ENV"))

    val system: ActorSystem[MediaLibActor.Command] =
      ActorSystem(MediaLibActor(mediaLibConfig), "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    api.getAll()(Timeout(10.seconds)).foreach { vids =>

      val (index, coll) = MediaLibScanner.scan(mediaLibConfig, vids)

      system.tell(AddMedia(index.sortBy(_.fileName)))
      system.tell(AddCollections(coll))

    }(system.executionContext)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }
}
