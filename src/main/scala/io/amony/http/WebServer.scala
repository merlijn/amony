package io.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{RejectionHandler, Route, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import io.amony.http.WebConversions._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.amony.actor.MediaLibActor.{ErrorResponse, InvalidCommand, Media, MediaNotFound}
import io.amony.http.WebModel.FragmentRange
import io.amony.lib.MediaLibApi
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
      (path("search") & parameters("q".optional, "offset".optional, "n".optional, "c".optional)) {
        (q, offset, s, c) =>
          get {
            val size         = s.map(_.toInt).getOrElse(defaultResultNumber)
            val searchResult = api.read.search(q, offset.map(_.toInt), size, c, None)
            val response     = searchResult.map(_.toWebModel().asJson)

            complete(response)
          }
      } ~ path("media" / Segment) { id =>
        get {
          onSuccess(api.read.getById(id)) {
            case Some(vid) => complete(vid.toWebModel.asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        } ~ delete {
          onSuccess(api.modify.deleteMedia(id)) {
            case _ => complete(StatusCodes.OK, "{}")
          }
        }
      } ~ path("tags") {
        get {
          complete(api.read.getTags().map(_.map(_.toWebModel.asJson)))
        }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(api.modify.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(api.modify.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(api.modify.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment / "tags") { (id, idx) =>
        (post & entity(as[List[String]])) { tags =>
          translateResponse(api.modify.updateFragmentTags(id, idx.toInt, tags))
        }
      }
    }

  def translateResponse(future: Future[Either[ErrorResponse, Media]]): Route = {
    onSuccess(future) {
      case Left(MediaNotFound(id))      => complete(StatusCodes.NotFound)
      case Left(InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
      case Right(media)                 => complete(media.toWebModel().asJson)
    }
  }

  val adminRoutes = pathPrefix("api" / "admin") {
    (path("regen-previews") & post) {
      api.admin.regeneratePreviews()
      complete("OK")
    } ~ (path("export-to-file") & post) {
      api.admin.exportLibrary()
      complete("OK")
    } ~ (path("verify-hashes") & post) {
      api.admin.verifyHashes()
      complete("OK")
    } ~ path("convert-non-streamable-videos") {
      api.admin.convertNonStreamableVideos()
      complete("OK")
    } ~ path("scan-library") {
      api.admin.scanLibrary()
      complete("OK")
    }
  }

  val thumbnailRoutes =
    path("files" / "thumbnails" / Segment) { id =>
      getFromFile(api.read.getThumbnailPathForMedia(id))
    }

  val videoRoutes = path("files" / "videos" / Segment) { id =>
    onSuccess(api.read.getById(id)) {
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

  val allRoutes = apiRoutes ~ adminRoutes ~ thumbnailRoutes ~ videoRoutes ~ clientFiles

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
