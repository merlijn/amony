package nl.amony.modules.search

import nl.amony.modules.search.solr.SolrConfig

case class SearchConfig(
  defaultNumberOfResults: Int,
  maximumNumberOfResults: Int,
  solr: SolrConfig
)
