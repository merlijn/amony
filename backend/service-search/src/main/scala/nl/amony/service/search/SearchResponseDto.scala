package nl.amony.service.search

import io.circe.Codec
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

import nl.amony.service.resources.web.dto.{ResourceDto, required}

case class SearchResponseDto(
  offset: Long,
  total: Long,
  @customise(required)
  results: Seq[ResourceDto],
  @customise(required)
  tags: Seq[String]
) derives Codec, Schema
