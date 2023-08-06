package nl.amony.search

import nl.amony.service.resources.web.ResourceWebModel.Media

case class WebSearchResponse(
  offset: Long,
  total: Long,
  media: Seq[Media],
  tags: Seq[String]
)
