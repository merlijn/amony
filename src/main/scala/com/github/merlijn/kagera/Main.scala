package com.github.merlijn.kagera

import akka.actor.typed.ActorSystem
import com.github.merlijn.kagera.actor.{MediaLibActor, MediaLibApi}
import com.github.merlijn.kagera.actor.MediaLibActor.{AddCollections, AddMedia}
import com.github.merlijn.kagera.http.WebServer
import com.github.merlijn.kagera.lib.MediaLibScanner
import scribe.Logging

object Main extends App with MainConfig with Logging {

  logger.info("ENV: " + System.getenv().get("ENV"))

  val system: ActorSystem[MediaLibActor.Command] =
    ActorSystem(MediaLibActor(mediaLibConfig), "mediaLibrary", config)

  val api = new MediaLibApi(mediaLibConfig, system)

  val (index, coll) = MediaLibScanner.scan(mediaLibConfig)

  system.tell(AddMedia(index.sortBy(_.fileName)))
  system.tell(AddCollections(coll))

  val webServer = new WebServer(webServerConfig, api)(system)

  webServer.run()
}
