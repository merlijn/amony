package nl.amony.search

import nl.amony.service.media.web.MediaWebModel.Video

case class WebSearchResponse(
  offset: Long,
  total: Long,
  videos: Seq[Video],
  tags: Seq[String]
)
