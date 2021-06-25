package com.github.merlijn.webapp

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import io.circe.syntax._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

import scala.util.{Failure, Success}

trait WebServer extends Logging {

  val mediaLib = new MediaLib(Config.path)

  implicit val system = ActorSystem(Behaviors.empty, "metube")

  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.executionContext

  val api =
    (path("api" / "videos") & parameters("q".optional)) { q =>
      get {

        val response = q match {
          case Some(query) => mediaLib.videoIndex.filter(_.fileName.contains(query))
          case None        => mediaLib.videoIndex
        }

        complete(HttpEntity(ContentTypes.`application/json`, response.asJson.toString))
      }
    }

  val thumbnails =
    path("files" / "thumbnails" / Segment) { name =>
      getFromFile(s"${Config.indexPath}/$name")
    }

  val videos = path("files" / "videos" / Segment) { id =>

    mediaLib.videoIndex.find(_.id == id) match {
        case None       => complete(StatusCodes.NotFound, "")
        case Some(info) => getFromFile(info.fileName)
      }
    }

  val bindingFuture =
    Http().newServerAt(Config.hostname, Config.port).bind(api ~ thumbnails ~ videos)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
    case Failure(ex) =>
      system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
      system.terminate()
  }
}
