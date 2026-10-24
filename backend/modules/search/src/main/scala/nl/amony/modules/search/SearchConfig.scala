package nl.amony.modules.search

case class SolrConfig(path: String, commitWithinMillis: Int)

case class SearchConfig(
  defaultNumberOfResults: Int,
  maximumNumberOfResults: Int,
  solr: SolrConfig
)
