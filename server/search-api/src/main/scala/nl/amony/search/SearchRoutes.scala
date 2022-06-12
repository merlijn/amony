package nl.amony.search

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import nl.amony.search.SearchProtocol._
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Route
import io.circe.syntax._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Codec, Encoder}
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import nl.amony.service.media.JsonCodecs
import nl.amony.service.media.MediaConfig.TranscodeSettings
import nl.amony.service.media.MediaWebModel.Video

import scala.concurrent.{ExecutionContext, Future}

object SearchRoutes {

  val durationPattern = raw"(\d*)-(\d*)".r

  case class SearchResponse(
     offset: Long,
     total: Long,
     videos: Seq[Video]
   )

  def apply(
      system: ActorSystem[Nothing],
      searchApi: SearchApi,
      config: SearchConfig,
      transcodingSettings: List[TranscodeSettings]
  ): Route = {

    implicit def executionContext: ExecutionContext = system.executionContext

    val jsonCodecs = new JsonCodecs(transcodingSettings)
    import jsonCodecs._

    implicit val searchResultEncoder: Encoder[SearchProtocol.SearchResult] =
      deriveEncoder[SearchResponse].contramapObject[SearchProtocol.SearchResult](result =>
        SearchResponse(result.offset, result.total, result.items.map(m => jsonCodecs.toWebModel(m)))
      )

    pathPrefix("api" / "search") {
      (path("media") & parameters(
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
      } ~ (path("fragments") & parameters("n".optional, "offset".optional, "tags".optional)) {
        (nParam, offsetParam, tag) =>
          get {

            val n      = nParam.map(_.toInt).getOrElse(config.defaultNumberOfResults)
            val offset = offsetParam.map(_.toInt).getOrElse(0)

            complete(searchApi.searchFragments(n, offset, tag).map {
              _.map { case (mediaId, f) => toWebModel(mediaId, f) }
            })
          }
      }
    }
  }
}
