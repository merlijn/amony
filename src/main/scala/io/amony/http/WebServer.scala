package io.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RejectionHandler, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import io.amony.actor.MediaLibApi
import io.amony.http.WebConversions._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

case class WebServerConfig(
    port: Int,
    hostName: String,
    hostClient: Boolean,
    clientPath: String,
    requestTimeout: FiniteDuration
)

class WebServer(val config: WebServerConfig, val mediaLibApi: MediaLibApi)(implicit val system: ActorSystem[Nothing])
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

  val api =
    pathPrefix("api") {
      (path("media") & parameters("q".optional, "offset".optional, "n".optional, "c".optional)) { (q, offset, s, c) =>
        get {

          val size         = s.map(_.toInt).getOrElse(defaultResultNumber)
          val searchResult = mediaLibApi.search(q, offset.map(_.toInt), size, c)
          val response     = searchResult.map(_.toWebModel().asJson)

          complete(response)
        }
      } ~ path("media" / Segment) { id =>
        get {
          mediaLibApi.getById(id) match {
            case Some(vid) => complete(vid.toWebModel.asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        }
      } ~ path("tags") {
        get {
          complete(mediaLibApi.getCollections().map(_.map(_.toWebModel.asJson)))
        }
      } ~ path("thumbnail" / Segment) { id =>
        (post & entity(as[Long])) { timeStamp =>
          logger.info(s"setting thumbnail for $id at $timeStamp")

          onSuccess(mediaLibApi.setThumbnailAt(id, timeStamp)) {
            case Some(vid) => complete(vid.toWebModel().asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        }
      } ~ pathPrefix("admin") {
        (path("regen-thumbnails") & post) {
          mediaLibApi.regenerateThumbnails()
          complete("OK")
        } ~ (path("export-to-file") & post) {
          mediaLibApi.exportLibrary()
          complete("OK")
        }
      }
    }

  val thumbnails =
    path("files" / "thumbnails" / Segment) { id =>
      getFromFile(mediaLibApi.getThumbnailPathForMedia(id))
    }

  val videos = path("files" / "videos" / Segment) { id =>
    mediaLibApi.getById(id) match {
      case None       => complete(StatusCodes.NotFound, "")
      case Some(info) => getFromFile(mediaLibApi.getFilePathForMedia(info))
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

  val apiRoutes = api ~ thumbnails ~ videos

  val routes =
    if (config.hostClient)
      apiRoutes ~ clientFiles
    else
      apiRoutes

//  val loggedRoutes =
//    DebuggingDirectives.logRequest("Webapp", Logging.InfoLevel)(routes)

  def run(): Unit = {

    val bindingFuture =
      Http().newServerAt(config.hostName, config.port).bind(routes)

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
