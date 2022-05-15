package nl.amony.http.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import nl.amony.WebServerConfig
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.MediaApi
import nl.amony.actor.media.MediaConfig.TranscodeSettings
import nl.amony.actor.media.MediaLibProtocol._
import nl.amony.api.SearchApi
import nl.amony.http.JsonCodecs
import nl.amony.http.WebModel.{FragmentRange, VideoMeta}
import scribe.Logging

import scala.concurrent.{ExecutionContext, Future}

object ApiRoutes extends Logging {

  def createRoutes(system: ActorSystem[Nothing],
                   mediaApi: MediaApi,
                   queryApi: SearchApi,
                   transcodingSettings: List[TranscodeSettings],
                   config: WebServerConfig): Route = {

    implicit def materializer: Materializer = Materializer.createMaterializer(system)
    implicit def executionContext: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)

    val jsonCodecs = new JsonCodecs(transcodingSettings)

    import jsonCodecs._

    def translateResponse(future: Future[Either[ErrorResponse, Media]]): Route = {
      onSuccess(future) {
        case Left(MediaNotFound(_))       => complete(StatusCodes.NotFound)
        case Left(InvalidCommand(reason)) => complete(StatusCodes.BadRequest, reason)
        case Right(media)                 => complete(media.asJson)
      }
    }

    val durationPattern = raw"(\d*)-(\d*)".r

    pathPrefix("api") {
      (path("search") & parameters(
        "q".optional,
        "offset".optional,
        "n".optional,
        "playlist".optional,
        "tags".optional,
        "min_res".optional,
        "d".optional,
        "sort_field".optional,
        "sort_dir".optional
      )) { (q, offset, n, playlist, tags, minResY, durationParam, sortParam, sortDir) =>
        get {
          val size        = n.map(_.toInt).getOrElse(config.defaultNumberOfResults)
          val sortDirection: SortDirection = sortDir match {
            case Some("desc") => Desc
            case _            => Asc
          }
          val sortField: SortField = sortParam
            .map {
              case "title"      => FileName
              case "size"       => FileSize
              case "duration"   => Duration
              case "date_added" => DateAdded
              case _            => throw new IllegalArgumentException("unkown sort field")
            }
            .getOrElse(FileName)

          val duration: Option[(Long, Long)] = durationParam.flatMap {
            case durationPattern("", "")   => None
            case durationPattern(min, "")  => Some((min.toLong, Long.MaxValue))
            case durationPattern("", max)  => Some((0, max.toLong))
            case durationPattern(min, max) => Some((min.toLong, max.toLong))
            case _                         => None
          }

          val searchResult: Future[SearchResult] =
            queryApi.searchMedia(q, offset.map(_.toInt), size, tags.toSet, playlist, minResY.map(_.toInt), duration, Sort(sortField, sortDirection))

          val response = searchResult.map(_.asJson)

          complete(response)
        }
      } ~ path("tags") {
        get {
          complete(queryApi.searchTags().map(_.asJson))
        }
      } ~ pathPrefix("media" / Segment) { id =>
        pathEnd {
          get {
            onSuccess(mediaApi.getById(id)) {
              case Some(media) => complete(media.asJson)
              case None        => complete(StatusCodes.NotFound)
            }
          } ~ (post & entity(as[VideoMeta])) { meta =>
            translateResponse(mediaApi.updateMetaData(id, meta.title, meta.comment, meta.tags))
          } ~ delete {
            onSuccess(mediaApi.deleteMedia(id, deleteResource = true)) { case _ =>
              complete(StatusCodes.OK, "{}")
            }
          }
        }
      } ~ (path("fragments" / "search") & parameters("n".optional, "offset".optional, "tags".optional)) {
        (nParam, offsetParam, tag) =>
          get {

            val n = nParam.map(_.toInt).getOrElse(config.defaultNumberOfResults)
            val offset = offsetParam.map(_.toInt).getOrElse(0)

            complete(queryApi.searchFragments(n, offset, tag).map {
              _.map { case (mediaId, f) => toWebModel(mediaId, f) }
            })
          }
      } ~ path("fragments" / Segment / "add") { (id) =>
        (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(mediaApi.addFragment(id, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment) { (id, idx) =>
        delete {
          translateResponse(mediaApi.deleteFragment(id, idx.toInt))
        } ~ (post & entity(as[FragmentRange])) { createFragment =>
          translateResponse(mediaApi.updateFragmentRange(id, idx.toInt, createFragment.from, createFragment.to))
        }
      } ~ path("fragments" / Segment / Segment / "tags") { (id, idx) =>
        (post & entity(as[List[String]])) { tags =>
          translateResponse(mediaApi.updateFragmentTags(id, idx.toInt, tags))
        }
      }
    }
  }
}
