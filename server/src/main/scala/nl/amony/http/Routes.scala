package nl.amony.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.{RejectionHandler, Route, ValidationRejection}
import akka.stream.Materializer
import akka.util.Timeout
import better.files.File
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import nl.amony.actor.MediaLibProtocol.{DateAdded, ErrorResponse, FileName, InvalidCommand, Media, MediaNotFound, Sort, VideoDuration}
import nl.amony.http.WebModel.FragmentRange
import nl.amony.lib.MediaLibApi
import io.circe.syntax._
import scribe.Logging

import scala.concurrent.{ExecutionContext, Future}

trait Routes extends JsonCodecs with Logging {

  val config: WebServerConfig
  val api: MediaLibApi
  implicit val system: ActorSystem[Nothing]

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
      (path("search") & parameters(
        "q".optional,
        "offset".optional,
        "n".optional,
        "tags".optional,
        "sort_field".optional,
        "sort_dir".optional,
        "min_res".optional
      )) { (q, offset, n, tags, sort, sortDir, minResY) =>
        get {
          val size         = n.map(_.toInt).getOrElse(defaultResultNumber)
          val sortReverse  = sortDir.map(_ == "desc").getOrElse(false)
          val sortField    = sort.map {
            case "title"      => FileName
            case "duration"   => VideoDuration
            case "date_added" => DateAdded
            case _          => throw new IllegalArgumentException("unkown sort field")
          }.getOrElse(FileName)

          val searchResult = api.query.search(q, offset.map(_.toInt), size, tags, minResY.map(_.toInt), Sort(sortField, sortReverse))
          val response     = searchResult.map(_.asJson)

          complete(response)
        }
      } ~ path("media" / Segment) { id =>
        get {
          onSuccess(api.query.getById(id)) {
            case Some(vid) => complete(vid.asJson)
            case None      => complete(StatusCodes.NotFound)
          }
        } ~ delete {
          onSuccess(api.modify.deleteMedia(id)) { case _ =>
            complete(StatusCodes.OK, "{}")
          }
        }
      } ~ path("tags") {
        get {
          complete(api.query.getTags().map(_.map(_.asJson)))
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
      case Right(media)                 => complete(media.asJson)
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
    } ~ (path("convert-non-streamable-videos") & post) {
      api.admin.convertNonStreamableVideos()
      complete("OK")
    } ~ (path("scan-library") & post) {
      api.admin.scanLibrary()
      complete("OK")
    }
  }

  val thumbnailRoutes =
    path("files" / "thumbnails" / Segment) { id =>
      getFromFile(api.query.getThumbnailPathForMedia(id))
    }

  val videoRoutes = path("files" / "videos" / Segment) { id =>
    onSuccess(api.query.getById(id)) {
      case None       => complete(StatusCodes.NotFound, "")
      case Some(info) => getFromFile(api.query.getFilePathForMedia(info))
    }
  }

  def webClientFiles: Route =
    rawPathPrefix(Slash) {

      extractUnmatchedPath { path =>
        // TODO sanitize
        val filePath = path.toString() match {
          case "" | "/" => "index.html"
          case other    => other
        }

        val targetFile = {
          val maybe = (File(config.webClientPath) / filePath)
          if (maybe.exists)
            maybe
          else
            File(config.webClientPath) / "index.html"
        }

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
    }

  val allApiRoutes =
    if (config.enableAdmin)
      apiRoutes ~ adminRoutes
    else
      apiRoutes

  val allRoutes = allApiRoutes ~ thumbnailRoutes ~ videoRoutes ~ webClientFiles
}
