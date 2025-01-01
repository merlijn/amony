package nl.amony.search

import io.circe.Codec
import nl.amony.service.resources.web.ResourceWebModel.ResourceDto
import sttp.tapir.Schema

case class SearchResponseDto(
  offset: Long,
  total: Long,
  results: Seq[ResourceDto],
  tags: Seq[String]
) derives Codec, Schema
