package nl.amony.search

import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import nl.amony.service.resources.web.JsonCodecs.{ _, given }
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.{Asc, Desc}
import nl.amony.service.search.api.{Query, SearchResult, SortField, SortOption}
import nl.amony.service.search.api.SortField._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe._

object SearchRoutes {

  val durationPattern = raw"(\d*)-(\d*)".r

  def getSortedTags(facetMap: Map[String, Long]): Seq[String] = {
    facetMap.toSeq
      .sortBy { case (key, count) => (-count, key) } // negative count for descending order
      .map(_._1)
  }

  given searchResultEncoder: Encoder[SearchResult] =
    deriveEncoder[WebSearchResponse].contramapObject[SearchResult](result => WebSearchResponse(result.offset, result.total, result.results.map(m => toDto(m)), getSortedTags(result.tags)))

  def apply(searchService: SearchService, config: SearchConfig): HttpRoutes[IO] = {

    HttpRoutes.of[IO] {
      case req @ GET -> Root / "api" / "search" / "media"  =>

        val params = req.params
        val q      = params.get("q")
        val offset = params.get("offset").map(_.toInt)
        val tags   = params.get("tags")
        val minRes = params.get("min_res").map(_.toInt)

        val sortDir = params.get("sort_dir").headOption match {
          case Some("desc") => Desc
          case _ => Asc
        }

        val sortField: SortField = req.params.get("sort_field")
          .map {
            case "title"      => Title
            case "size"       => Size
            case "duration"   => Duration
            case "date_added" => DateAdded
            case _ => throw new IllegalArgumentException("unknown sort field")
          }
          .getOrElse(Title)

        val duration: Option[(Long, Long)] = params.get("d").flatMap {
          case durationPattern("", "") => None
          case durationPattern(min, "") => Some((min.toLong, Long.MaxValue))
          case durationPattern("", max) => Some((0, max.toLong))
          case durationPattern(min, max) => Some((min.toLong, max.toLong))
          case _ => None
        }

        val size = params.get("n").map(_.toInt).getOrElse(config.defaultNumberOfResults)

        val query = Query(
          q = q,
          n = size,
          offset = offset,
          tags = tags.toSeq,
          playlist = None,
          minRes = minRes,
          maxRes = None,
          minDuration = duration.map(_._1),
          maxDuration = duration.map(_._2),
          sort = Some(SortOption(sortField, sortDir))
        )

        IO.fromFuture(IO(searchService.searchMedia(query)))
          .map(_.asJson)
          .flatMap(Ok(_))
    }
  }
}
