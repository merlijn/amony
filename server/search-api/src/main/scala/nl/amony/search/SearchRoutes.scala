package nl.amony.search

import cats.effect.IO
import nl.amony.service.auth.{Authenticator, JwtDecoder, SecurityError}
import nl.amony.service.auth.tapir.*
import nl.amony.service.resources.web.dto.toDto
import nl.amony.service.resources.web.oneOfList
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.{Asc, Desc}
import nl.amony.service.search.api.SortField.*
import nl.amony.service.search.api.{Query, SortField, SortOption}
import org.http4s.HttpRoutes
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

object SearchRoutes:

  case class SearchQueryInput(
    q: Option[String],
    n: Option[Int],
    d: Option[String],
    sortField: Option[String],
    sortDir: Option[String],
    minRes: Option[Int],
    offset: Option[Int],
    tag: Option[String]
  )

  val q         = query[Option[String]]("q").description("The search query").example(Some("cats"))
  val n         = query[Option[Int]]("n").description("The number of results to return").example(Some(10))
  val d         = query[Option[String]]("d").description("The duration range in minutes").example(Some("10-30"))
  val sortField = query[Option[String]]("sort_field").description("Indicates what field to sort").example(Some("title"))
  val sortDir   = query[Option[String]]("sort_dir").description("Indicates which direction to sort").example(Some("asc"))
  val minRes    = query[Option[Int]]("min_res").description("The minimum (vertical) resolution").example(Some(720))
  val offset    = query[Option[Int]]("offset").description("The offset for the search results").example(Some(12))
  val tag       = query[Option[String]]("tag").description("A comma separated list of tags")

  val errorOutput: EndpointOutput[SecurityError] = oneOfList(securityErrors)

  val searchResourcesEndpoint: Endpoint[SecurityInput, SearchQueryInput, SecurityError, SearchResponseDto, Any] =
    endpoint
      .name("findResources")
      .tag("search")
      .description("Find resources using a search query")
      .get.in("api" / "search" / "media" / q and n and d and sortField and sortDir and minRes and offset and tag).mapInTo[SearchQueryInput]
      .securityIn(securityInput)
      .errorOut(errorOutput)
      .out(jsonBody[SearchResponseDto])

  val endpoints = List(searchResourcesEndpoint)

  private def getSortedTags(facetMap: Map[String, Long]): Seq[String] = {
    facetMap.toSeq
      .sortBy { case (key, count) => (-count, key) } // negative count for descending order
      .map(_._1)
  }

  private val durationPattern = raw"(\d*)-(\d*)".r

  def apply(searchService: SearchService, config: SearchConfig, jwtDecoder: JwtDecoder)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    val security = Authenticator(jwtDecoder)

    def sanitize(s: String, maxLength: Int): String = {
      s.filter(c => c.isLetterOrDigit || c.isWhitespace).take(maxLength)
    }

    val routeImpl = searchResourcesEndpoint
      .serverSecurityLogic(security.publicEndpoint)
      .serverLogic { auth => queryDto =>

        val duration: Option[(Long, Long)] = queryDto.d.flatMap {
          case durationPattern("", "") => None
          case durationPattern(min, "") => Some((min.toLong, Long.MaxValue))
          case durationPattern("", max) => Some((0, max.toLong))
          case durationPattern(min, max) => Some((min.toLong, max.toLong))
          case _ => None
        }

        val sortField: SortField = queryDto.sortField
          .map {
            case "title" => Title
            case "size" => Size
            case "duration" => Duration
            case "date_added" => DateAdded
            case _ => throw new IllegalArgumentException("unknown sort field")
          }
          .getOrElse(Title)

        val sortDir = queryDto.sortDir match {
          case Some("desc") => Desc
          case _ => Asc
        }

        val query = Query(
          q = queryDto.q.map(s => sanitize(s, 64)),
          n = queryDto.n.getOrElse(config.defaultNumberOfResults),
          offset = queryDto.offset,
          tags = queryDto.tag.map(s => sanitize(s, 32)).toList,
          playlist = None,
          minRes = queryDto.minRes,
          maxRes = None,
          minDuration = duration.map(_._1),
          maxDuration = duration.map(_._2),
          sort = Some(SortOption(sortField, sortDir))
        )

        IO.fromFuture(IO(searchService.searchMedia(query))).map { response =>
          Right(
            SearchResponseDto(
              offset = response.offset,
              total = response.total,
              results = response.results.map(toDto),
              tags = getSortedTags(response.tags)
            )
          )
        }
      }

    Http4sServerInterpreter[IO](serverOptions).toRoutes(List(routeImpl))
  }