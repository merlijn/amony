package nl.amony.http.routes

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import nl.amony.actor.index.QueryProtocol._
import nl.amony.api.SearchApi
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import nl.amony.WebServerConfig
import io.circe.syntax._
import nl.amony.actor.media.MediaConfig.TranscodeSettings
import nl.amony.http.JsonCodecs
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object SearchRoutes {

  val durationPattern = raw"(\d*)-(\d*)".r

  def createRoutes(
      system: ActorSystem[Nothing],
      searchApi: SearchApi,
      config: WebServerConfig,
      transcodingSettings: List[TranscodeSettings]
  ): Route = {

    implicit def executionContext: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout.durationToTimeout(config.requestTimeout)

    val jsonCodecs = new JsonCodecs(transcodingSettings)
    import jsonCodecs._

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
          val size = n.map(_.toInt).getOrElse(config.defaultNumberOfResults)
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
            searchApi.searchMedia(
              q,
              offset.map(_.toInt),
              size,
              tags.toSet,
              playlist,
              minResY.map(_.toInt),
              duration,
              Sort(sortField, sortDirection)
            )

          val response = searchResult.map(_.asJson)

          complete(response)
        }
      } ~ path("tags") {
        get {
          complete(searchApi.searchTags().map(_.asJson))
        }
      }
    }
  }
}
