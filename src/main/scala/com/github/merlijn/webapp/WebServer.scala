package com.github.merlijn.webapp

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.github.merlijn.webapp.Model.Video
import io.circe.syntax._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

import scala.util.{Failure, Success}

trait WebServer extends Logging {

  val index = Lib.index(Config.path, 9)

  index.foreach { i =>

    Lib.writeThumbnail(i.fileName, i.duration / 3, Some(s"${Config.indexPath}/${i.id}.jpeg"))
  }

  val videos: List[Video] =
    index.map { info => 
      Video(
        id        = info.id,
        title     = info.fileName,
        thumbnail = s"/files/thumbnails/${info.id}.jpeg",
        tags      = Seq.empty
      ) 
    }.toList

  val videosJson: String = videos.asJson.toString

  implicit val system = ActorSystem(Behaviors.empty, "my-system")

  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.executionContext

  val route =
    path("api" / "movies") {
      get {
        complete(HttpEntity(ContentTypes.`application/json`, videosJson))
      }
    } ~ {
      path("files" / "thumbnails" / Segment) { name =>
        getFromFile(s"${Config.indexPath}/$name") // uses implicit ContentTypeResolver
      }
    } ~ path("files" / "videos" / Segment) { name =>

      logger.info("---")
      val id = name.substring(0, name.lastIndexOf('.'))

      index.find(_.id == id) match {
        case None       => complete(StatusCodes.NotFound, "")
        case Some(info) =>
          logger.info("video request")
          getFromFile(info.fileName)
      }
    }

  val bindingFuture =
    Http().newServerAt(Config.hostname, Config.port).bind(route)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
    case Failure(ex) =>
      system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
      system.terminate()
  }
}
