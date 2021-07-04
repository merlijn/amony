package com.github.merlijn.webapp

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import better.files.File
import io.circe.syntax._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.util.{Failure, Success}

trait WebServer extends Logging {

  val mediaLibConfig = MediaLibConfig(Config.library.path, Config.library.indexPath, 4)
  val mediaLib = new MediaLib(mediaLibConfig)

  implicit val system = ActorSystem(Behaviors.empty, "webapp")
  implicit val materializer = Materializer.createMaterializer(system)
  implicit val executionContext = system.executionContext

  val api =
      pathPrefix("api") {
        (path("videos") & parameters("q".optional, "p".optional, "s".optional, "c".optional)) { (q, p, s, c) =>
          get {

            val size = s.map(_.toInt).getOrElse(24)
            val page = p.map(_.toInt).getOrElse(1)

            val response = mediaLib.search(q, page, size, c.map(_.toInt))

            complete(HttpEntity(ContentTypes.`application/json`, response.asJson.toString))
          }
        } ~ path("collections") {

          get {
            complete(HttpEntity(ContentTypes.`application/json`, mediaLib.collections.asJson.toString))
          }
        } ~ path("thumbnail" / Segment) { id =>
          (post & entity(as[Long])) { timeStamp =>
              logger.info(s"setting thumbnail for $id at $timeStamp")
              mediaLib.setThumbnailAt(id, timeStamp) match {
                case Some(vid) => complete(vid.asJson)
                case None      => complete(StatusCodes.NotFound)
              }
          }
        }
      }

  val thumbnails =
    path("files" / "thumbnails" / Segment) { name =>
      getFromFile(s"${Config.library.indexPath}/$name")
    }

  val videos = path("files" / "videos" / Segment) { id =>

    mediaLib.getById(id) match {
        case None       => complete(StatusCodes.NotFound, "")
        case Some(info) => getFromFile((mediaLib.libraryDir / info.fileName).path.toAbsolutePath.toString)
      }
    }

  def clientFiles = rawPathPrefix(Slash) {

    extractUnmatchedPath { path =>

      // TODO sanitize
       val filePath = path.toString() match {
         case "" | "/" => "index.html"
         case other    => other
       }

        val targetFile = {

          val maybe = (File(Config.http.clientPath) / filePath)
          if (maybe.exists)
            maybe
          else
            (File(Config.http.clientPath) / "index.html")
        }

        logger.info(s"target: ${targetFile.path.toAbsolutePath.toString}")

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
  }

  val apiRoutes = api ~ thumbnails ~ videos

  val routes =
    if (Config.http.hostClient)
      apiRoutes ~ clientFiles
    else
      apiRoutes

  val bindingFuture =
    Http().newServerAt(Config.http.hostname, Config.http.port).bind(routes)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
    case Failure(ex) =>
      system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
      system.terminate()
  }
}
