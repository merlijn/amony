package nl.amony.service.search

import io.circe.Codec
import nl.amony.service.resources.web.dto.{ResourceDto, required}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

case class SearchResponseDto(
  offset: Long,
  total: Long,
  @customise(required)
  results: Seq[ResourceDto],
  @customise(required)
  tags: Seq[String]
) derives Codec, Schema
