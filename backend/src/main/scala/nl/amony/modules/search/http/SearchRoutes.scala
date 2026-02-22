package nl.amony.modules.search.http

import scala.util.Try

import cats.effect.IO
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.http.{oneOfList, toDto}
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.api.*
import nl.amony.modules.search.api.SortDirection.{Asc, Desc}
import nl.amony.modules.search.api.SortField.*

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
    u: Option[String],
    sort: Option[String],
    minRes: Option[Int],
    offset: Option[Int],
    tag: Option[String],
    untagged: Option[Boolean]
  )

  val q        = query[Option[String]]("q").description("The search query").example(Some("cats"))
  val n        = query[Option[Int]]("n").description("The number of results to return").example(Some(10))
  val d        = query[Option[String]]("d").description("The duration range in minutes").example(Some("10-30"))
  val u        = query[Option[String]]("u").description("The upload date range (milliseconds since UTC)").example(Some("1771751993045-"))
  val sort     = query[Option[String]]("sort").description("Sort field and direction (e.g., title-asc, random-12345)").example(Some("title-asc"))
  val minRes   = query[Option[Int]]("min_res").description("The minimum (vertical) resolution").example(Some(720))
  val offset   = query[Option[Int]]("offset").description("The offset for the search results").example(Some(12))
  val tag      = query[Option[String]]("tag").description("An optional tag")
  val untagged = query[Option[Boolean]]("untagged").description("Only return resources without tags").example(Some(false))

  val searchResourcesEndpoint: Endpoint[SecurityInput, SearchQueryInput, ApiError | SecurityError, SearchResponseDto, Any] = endpoint
    .name("findResources").tag("search").description("Find resources using a search query").get
    .in("api" / "search" / "media" / q and n and d and u and sort and minRes and offset and tag and untagged).mapInTo[SearchQueryInput]
    .securityIn(securityInput).errorOut(errorOutput).out(jsonBody[SearchResponseDto])

  val endpoints = List(searchResourcesEndpoint)

  private def getSortedTags(facetMap: Map[String, Long]): Seq[String] =
    facetMap.toSeq
      .sortBy { case (key, count) => (-count, key) } // negative count for descending order
      .map(_._1)

  private val durationPattern = raw"(\d*)-(\d*)".r
  private val randomPattern   = raw"random-(\d{5})".r
  private val sortPattern     = raw"(\w+)(?:-(asc|desc))?".r

  def apply(searchService: SearchService, config: SearchConfig, apiSecurity: ApiSecurity)(
    using serverOptions: Http4sServerOptions[IO]
  ): HttpRoutes[IO] = {

    def sanitize(s: String, maxLength: Int, isCharAllowed: Char => Boolean): String = s.filter(isCharAllowed).take(maxLength)

    val routeImpl = searchResourcesEndpoint.serverSecurityLogicPure(apiSecurity.publicEndpoint).serverLogic {
      auth => queryDto =>

        def parseRange(s: Option[String]): (Option[Long], Option[Long]) = s match
          case Some(durationPattern("", ""))   => (None, None)
          case Some(durationPattern(min, ""))  => (Try(min.toLong).toOption, None)
          case Some(durationPattern("", max))  => (None, Try(max.toLong).toOption)
          case Some(durationPattern(min, max)) => (Try(min.toLong).toOption, Try(max.toLong).toOption)
          case _                               => (None, None)

        val (minDuration, maxDuration)     = parseRange(queryDto.d)
        val (minUploadDate, maxUploadDate) = parseRange(queryDto.u)

        val sortOption: SortOption = queryDto.sort.flatMap {
          case randomPattern(seedStr)  =>
            Try(seedStr.toInt).toOption.map(seed => SortOption(SortField.Random(seed), Desc))
          case sortPattern(field, dir) =>
            val sortField: SortField = field match
              case "title"      => Title
              case "size"       => Size
              case "duration"   => Duration
              case "date_added" => DateAdded
              case _            => Title
            val sortDir              = if dir == "desc" then Desc else Asc
            Some(SortOption(sortField, sortDir))
          case _                       => None
        }.getOrElse(SortOption(Title, Asc))

        val query = Query(
          q               = queryDto.q.map(s => sanitize(s, 64, c => c.isLetterOrDigit || c.isWhitespace)),
          n               = Math.min(queryDto.n.getOrElse(config.defaultNumberOfResults), config.maximumNumberOfResults),
          offset          = queryDto.offset.map(n => Math.max(0, n)),
          includeTags     = if queryDto.untagged.contains(true) then Set.empty else queryDto.tag.map(s => sanitize(s, 32, c => c.isLetterOrDigit)).toSet,
          excludeTags     = apiSecurity.userAccess(auth).hiddenTags,
          excludeBuckets  = apiSecurity.userAccess(auth).hiddenBuckets,
          resolutionRange = ResolutionRange(min = queryDto.minRes, max = None),
          durationRange   = DurationRange(minDuration, maxDuration),
          uploadDateRange = UploadDateRange(minUploadDate, maxUploadDate),
          sort            = Some(sortOption),
          untagged        = queryDto.untagged.filter(identity)
        )

        searchService.searchMedia(query).map { response =>
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
