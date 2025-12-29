package nl.amony.modules.search

import io.circe.Codec
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

import nl.amony.modules.resources.web.{ResourceDto, required}

case class SearchResponseDto(
  offset: Long,
  total: Long,
  @customise(required)
  results: Seq[ResourceDto],
  @customise(required)
  tags: Seq[String]
) derives Codec, Schema
