package io.amony

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import io.amony.actor.MediaLibActor
import io.amony.http.WebServer
import io.amony.lib.{MediaLibApi, MediaLibScanner}
import scribe.Logging

import scala.concurrent.duration.DurationInt

object App extends AppConfig with Logging {

  def main(args: Array[String]): Unit = {

    val system: ActorSystem[MediaLibActor.Command] =
      ActorSystem(MediaLibActor(mediaLibConfig), "mediaLibrary", config)

    val api = new MediaLibApi(mediaLibConfig, system)

    api.admin.scanLibrary()(10.seconds)

    logEncodings()

//    MediaLibScanner.convertNonStreamableVideos(mediaLibConfig, api)

//    Migration.importFromFile(system)

    val webServer = new WebServer(webServerConfig, api)(system)

    webServer.run()
  }

  def logEncodings() = {
    import java.nio.charset.Charset
    logger.info("file.encoding=" + System.getProperty("file.encoding"))
    logger.info("Default Charset=" + Charset.defaultCharset)
    logger.info("Default Charset in Use=" + getDefaultCharSet)

    import java.io.ByteArrayOutputStream
    import java.io.OutputStreamWriter
    def getDefaultCharSet = {
      val writer = new OutputStreamWriter(new ByteArrayOutputStream)
      writer.getEncoding
    }
  }
}
