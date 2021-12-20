package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import nl.amony.actor.MediaIndex._
import nl.amony.actor.MediaLibProtocol._
import nl.amony.http.JsonCodecs
import nl.amony.http.RouteDeps
import nl.amony.http.WebModel.FragmentRange
import nl.amony.http.WebModel.VideoMeta
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ApiRoutes extends Logging with IdentityRoutes {

  self: RouteDeps =>

  val defaultResultNumber = 24

  val apiRoutes =
    pathPrefix("api") {
      (path("search") & parameters(
        "q".optional,
        "offset".optional,
        "n".optional,
        "playlist".optional,
        "tags".optional,
        "sort_field".optional,
        "sort_dir".optional,
        "min_res".optional
      )) { (q, offset, n, playlist, tags, sort, sortDir, minResY) =>
        get {
          val size        = n.map(_.toInt).getOrElse(defaultResultNumber)
          val sortReverse = sortDir.map(_ == "desc").getOrElse(false)
          val sortField = sort
            .map {
              case "title"      => FileName
              case "duration"   => Duration
              case "date_added" => DateAdded
              case _            => throw new IllegalArgumentException("unkown sort field")
            }
            .getOrElse(FileName)

          val searchResult =
            api.query.search(q, offset.map(_.toInt), size, tags.toSet, playlist, minResY.map(_.toInt), Sort(sortField, sortReverse))

          val response = searchResult.map(_.asJson)

          complete(response)
        }
      } ~ path("tags") {
        get {
          complete(api.query.getTags().map(_.asJson))
        }
      } ~ path("playlists") {
        get {
          complete(api.query.getPlaylists().map(_.map(_.asJson)))
        }
      } ~ pathPrefix("media" / Segment) { id =>
        pathEnd {
          get {
            onSuccess(api.query.getById(id)) {
              case Some(vid) => complete(vid.asJson)
              case None      => complete(StatusCodes.NotFound)
            }
          } ~ (post & entity(as[VideoMeta])) { meta =>
            translateResponse(api.modify.updateMetaData(id, meta.title, meta.comment, meta.tags))
          } ~ delete {
            onSuccess(api.modify.deleteMedia(id, deleteFile = true)) { case _ =>
              complete(StatusCodes.OK, "{}")
            }
          }
        }
      } ~ (path("fragments" / "search") & parameters("n".optional, "offset".optional)) { (n, offset) =>
        get {
          complete(api.query.searchFragments(n.map(_.toInt).getOrElse(5), 0, "nature").map(_.map(toWebModel)))
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
}
