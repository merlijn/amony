package nl.amony.search

import nl.amony.service.media.web.MediaWebModel.Media

case class WebSearchResponse(
  offset: Long,
  total: Long,
  media: Seq[Media],
  tags: Seq[String]
)
