package nl.amony.search

import nl.amony.service.resources.web.ResourceWebModel.ResourceDto

case class WebSearchResponse(
                              offset: Long,
                              total: Long,
                              results: Seq[ResourceDto],
                              tags: Seq[String]
)
