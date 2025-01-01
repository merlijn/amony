package nl.amony.search

import nl.amony.service.auth.tapir.securityInput
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.model.{CommaSeparated, Delimited}

object SearchEndpoints:

  case class SearchQueryInput(
    q: Option[String],
    n: Option[Int],
    d: Option[String],
    offset: Option[Int],
    tags: Delimited[",", String]
  )

  val q = query[Option[String]]("q").description("The search query").example(Some("cats"))
  val n = query[Option[Int]]("n").description("The number of results to return").example(Some(10))
  val d = query[Option[String]]("d").description("The duration range in minutes").example(Some("10-30"))
  val offset = query[Option[Int]]("offset").description("The offset for the search results").example(Some(12))
  val tags = query[CommaSeparated[String]]("tags").description("A comma separated list of tags")

  val searchResources =
    endpoint
      .name("findResources")
      .description("Find resources using a search query")
      .get.in("api" / "search" / q and n and d and offset and tags).mapInTo[SearchQueryInput]
      .securityIn(securityInput)
      .out(jsonBody[SearchResponseDto])
