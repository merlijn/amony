package io.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{RejectionHandler, Route, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import io.amony.actor.MediaLibApi
import io.amony.http.WebConversions._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.amony.actor.MediaLibActor.{ErrorResponse, InvalidCommand, Media, MediaNotFound}
import io.amony.http.WebModel.CreateFragment
import io.circe.syntax._
import scribe.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

case class WebServerConfig(
    port: Int,
    hostName: String,
    hostClient: Boolean,
    clientPath: String,
    requestTimeout: FiniteDuration
)

class WebServer(val config: WebServerConfig, val api: MediaLibApi)(implicit val system: ActorSystem[Nothing])
    extends Logging
    with JsonCodecs {

  implicit def materializer: Materializer = Materializer.createMaterializer(system)
  implicit def executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)

  val defaultResultNumber = 24

  val rejectionHandler = RejectionHandler
    .newBuilder()
    .handleNotFound { complete(StatusCodes.NotFound, """{"statusCode" : 404 }""") }
    .handle { case ValidationRejection(msg, _) => complete(StatusCodes.InternalServerError, msg) }
    .result()

  val apiRoutes =
    pathPrefix("api") {
      (path("media") & parameters("q".optional, "offset".optional, "n".optional, "c".optional)) { (q, offset, s, c) =>
        get {

          val size         = s.map(_.toInt).getOrElse(defaultResultNumber)
          val searchResult = api.read.search(q, offset.map(_.toInt), size, c)
          val response     = searchResult.map(_.toWebModel().asJson)

          complete(response)
        }
      } ~ path("media" / Segment) { id =>
        get {
          api.read.getById(id) match {
            case Some(vid) => complete(vid.toWebModel.asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        }
      } ~ path("tags") {
        get {
          complete(api.read.getTags().map(_.map(_.toWebModel.asJson)))
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[CreateFragment])) { createFragment =>
          mediaResponse(api.modify.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          mediaResponse(api.modify.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[CreateFragment])) { createFragment =>
          mediaResponse(api.modify.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      }
    }


  def mediaResponse(future: Future[Either[ErrorResponse, Media]]): Route = {
    onSuccess(future) {
      case Left(MediaNotFound(id))      => complete(StatusCodes.NotFound)
      case Left(InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
      case Right(media)                 => complete(media.toWebModel().asJson)
    }
  }

  val adminRoutes = pathPrefix("api" / "admin") {
    (path("regen-thumbnails") & post) {
      api.admin.regeneratePreviews()
      complete("OK")
    } ~ (path("export-to-file") & post) {
      api.admin.exportLibrary()
      complete("OK")
    } ~ (path("verify-hashes") & post) {
      api.admin.verifyHashes()
      complete("OK")
    }
  }

  val thumbnails =
    path("files" / "thumbnails" / Segment) { id =>
      getFromFile(api.read.getThumbnailPathForMedia(id))
    }

  val videos = path("files" / "videos" / Segment) { id =>
    api.read.getById(id) match {
      case None       => complete(StatusCodes.NotFound, "")
      case Some(info) => getFromFile(api.read.getFilePathForMedia(info))
    }
  }

  def clientFiles =
    rawPathPrefix(Slash) {

      extractUnmatchedPath { path =>
        // TODO sanitize
        val filePath = path.toString() match {
          case "" | "/" => "index.html"
          case other    => other
        }

        val targetFile = {
          val maybe = (File(config.clientPath) / filePath)
          if (maybe.exists)
            maybe
          else
            File(config.clientPath) / "index.html"
        }

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
    }

  val allRoutes = apiRoutes ~ adminRoutes ~ thumbnails ~ videos ~ clientFiles

//  val loggedRoutes =
//    DebuggingDirectives.logRequest("Webapp", Logging.InfoLevel)(routes)

  def run(): Unit = {

    val bindingFuture =
      Http().newServerAt(config.hostName, config.port).bind(allRoutes)

    bindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }
}
