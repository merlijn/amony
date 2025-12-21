package nl.amony.service.search

import scala.util.Try

import cats.effect.IO
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.{query, *}

import nl.amony.lib.auth.*
import nl.amony.service.resources.web.dto.toDto
import nl.amony.service.resources.web.oneOfList
import nl.amony.service.search.domain.SortDirection.{Asc, Desc}
import nl.amony.service.search.domain.SortField.*
import nl.amony.service.search.domain.{Query, SearchService, SortField, SortOption}

object SearchRoutes:

  enum ApiError:
    case NotFound, BadRequest

  val apiErrorOutputs = List(
    oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(ApiError.NotFound),
    oneOfVariantSingletonMatcher(statusCode(StatusCode.BadRequest))(ApiError.BadRequest)
  )

  val errorOutput: EndpointOutput[ApiError | SecurityError] = oneOfList(securityErrors ++ apiErrorOutputs)

  case class SearchQueryInput(
    q: Option[String],
    n: Option[Int],
    d: Option[String],
    sortField: Option[String],
    sortDir: Option[String],
    minRes: Option[Int],
    offset: Option[Int],
    tag: Option[String],
    untagged: Option[Boolean]
  )

  val q         = query[Option[String]]("q").description("The search query").example(Some("cats"))
  val n         = query[Option[Int]]("n").description("The number of results to return").example(Some(10))
  val d         = query[Option[String]]("d").description("The duration range in minutes").example(Some("10-30"))
  val sortField = query[Option[String]]("sort_field").description("Indicates what field to sort").example(Some("title"))
  val sortDir   = query[Option[String]]("sort_dir").description("Indicates which direction to sort").example(Some("asc"))
  val minRes    = query[Option[Int]]("min_res").description("The minimum (vertical) resolution").example(Some(720))
  val offset    = query[Option[Int]]("offset").description("The offset for the search results").example(Some(12))
  val tag       = query[Option[String]]("tag").description("An optional tag")
  val untagged  = query[Option[Boolean]]("untagged").description("Only return resources without tags").example(Some(false))

  val searchResourcesEndpoint: Endpoint[SecurityInput, SearchQueryInput, ApiError | SecurityError, SearchResponseDto, Any] = endpoint
    .name("findResources").tag("search").description("Find resources using a search query").get
    .in("api" / "search" / "media" / q and n and d and sortField and sortDir and minRes and offset and tag and untagged).mapInTo[SearchQueryInput]
    .securityIn(securityInput).errorOut(errorOutput).out(jsonBody[SearchResponseDto])

  val endpoints = List(searchResourcesEndpoint)

  private def getSortedTags(facetMap: Map[String, Long]): Seq[String] = facetMap.toSeq
    .sortBy { case (key, count) => (-count, key) } // negative count for descending order
    .map(_._1)

  private val durationPattern = raw"(\d*)-(\d*)".r

  def apply(searchService: SearchService, config: SearchConfig, apiSecurity: ApiSecurity)(
    using serverOptions: Http4sServerOptions[IO]
  ): HttpRoutes[IO] = {

    def sanitize(s: String, maxLength: Int, isCharAllowed: Char => Boolean): String = s.filter(isCharAllowed).take(maxLength)

    val routeImpl = searchResourcesEndpoint.serverSecurityLogicPure(apiSecurity.publicEndpoint).serverLogic {
      auth => queryDto =>

        val duration: Option[(Long, Long)] = queryDto.d.flatMap:
          case durationPattern("", "")   => None
          case durationPattern(min, "")  => Try((min.toLong, Long.MaxValue)).toOption
          case durationPattern("", max)  => Try((0L, max.toLong)).toOption
          case durationPattern(min, max) => Try((min.toLong, max.toLong)).toOption
          case _                         => None

        val sortField: SortField = queryDto.sortField.map {
          case "title"      => Title
          case "size"       => Size
          case "duration"   => Duration
          case "date_added" => DateAdded
          case _            => Title
        }.getOrElse(Title)

        val sortDir = queryDto.sortDir match {
          case Some("desc") => Desc
          case _            => Asc
        }

        val query = Query(
          q           = queryDto.q.map(s => sanitize(s, 64, c => c.isLetterOrDigit || c.isWhitespace)),
          n           = Math.min(queryDto.n.getOrElse(config.defaultNumberOfResults), config.maximumNumberOfResults),
          offset      = queryDto.offset.map(n => Math.max(0, n)),
          tags        = if queryDto.untagged.contains(true) then List.empty else queryDto.tag.map(s => sanitize(s, 32, c => c.isLetterOrDigit)).toList,
          playlist    = None,
          minRes      = queryDto.minRes.map(n => Math.max(0, n)),
          maxRes      = None,
          minDuration = duration.map(_._1),
          maxDuration = duration.map(_._2),
          sort        = Some(SortOption(sortField, sortDir)),
          untagged    = queryDto.untagged.filter(identity)
        )

        searchService.searchMedia(query).map {
          response =>
            Right(SearchResponseDto(
              offset  = response.offset,
              total   = response.total,
              results = response.results.map(toDto),
              tags    = getSortedTags(response.tags)
            ))
        }
    }

    Http4sServerInterpreter[IO](serverOptions).toRoutes(List(routeImpl))
  }
