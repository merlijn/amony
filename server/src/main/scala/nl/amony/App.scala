package nl.amony

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import nl.amony.actor.MainRouter
import nl.amony.actor.MediaLibProtocol
import nl.amony.actor.Message
import nl.amony.actor.MediaLibProtocol.Command
import nl.amony.http.WebServer
import nl.amony.lib.{AmonyApi, FFMpeg, MediaScanner, Migration}
import scribe.Logging

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import scala.concurrent.ExecutionContext

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

    val scanner                      = new MediaScanner(appConfig)
    val router: Behavior[Message]    = MainRouter.apply(appConfig.media, scanner)
    val system: ActorSystem[Message] = ActorSystem(router, "mediaLibrary", config)

    val api = new AmonyApi(appConfig, scanner, system)

    api.admin.scanLibrary()(10.seconds)

//    probeAll(api)(system.executionContext)

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    Migration.importFromExport(api)(10.seconds)

    val webServer = new WebServer(appConfig.api, api)(system)

    webServer.run()
  }

  def probeAll(api: AmonyApi)(implicit ec: ExecutionContext): Unit = {

    val media = Await.result(api.query.getAll()(10.seconds), 10.seconds)

    logger.warn("Probing all videos")

    val (fastStart, nonFastStart) = media.partition { m =>
      val path       = m.resolvePath(api.config.media.mediaPath)
      val (_, debug) = FFMpeg.ffprobe(path)
      debug.isFastStart
    }

    logger.warn(s"videos optimized for faststart: ${fastStart.size}")

    nonFastStart.foreach { m =>
      logger.warn(s"not optimized for streaming: ${m.fileInfo.relativePath}")
    }
  }
}
