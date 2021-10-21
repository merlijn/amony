package nl.amony

import akka.actor.typed.{ActorSystem, Behavior}
import nl.amony.actor.{MainRouter, MediaLibProtocol}
import nl.amony.actor.MediaLibProtocol.Command
import nl.amony.http.WebServer
import nl.amony.lib.{FFMpeg, MediaLibApi}
import scribe.Logging

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

//    val router: Behavior[Command] = MediaLibProtocol(mediaLibConfig)
    val router: Behavior[Any] = MainRouter.apply(mediaLibConfig)
    val system: ActorSystem[Any] = ActorSystem(router, "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    api.admin.scanLibrary()(10.seconds)

//    probeAll(api)(system.executionContext)

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    Migration.importFromExport(api)(10.seconds)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }

  def probeAll(api: MediaLibApi)(implicit ec: ExecutionContext): Unit = {

    val media = Await.result(api.query.getAll()(10.seconds), 10.seconds)

    logger.warn("Probing all videos")

    val (fastStart, nonFastStart) = media.partition { m =>
      val path  = m.resolvePath(api.config.libraryPath)
      val probe = FFMpeg.ffprobe(path)
      probe.fastStart
    }

    logger.warn(s"videos optimized for faststart: ${fastStart.size}")

    nonFastStart.foreach { m =>
      logger.warn(s"not optimized for streaming: ${m.fileInfo.relativePath}")
    }
  }
}
