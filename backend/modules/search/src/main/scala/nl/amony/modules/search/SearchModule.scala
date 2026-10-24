package nl.amony.modules.search

import cats.effect.{IO, Resource}

import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.resources.api.{BucketRegistry, ResourceIndex}
import nl.amony.modules.search.api.{SearchIndexer, SearchService}
import nl.amony.modules.search.http.SearchRoutes
import nl.amony.modules.search.solr.{SolrIndexer, SolrResource, SolrSearchService}
import nl.amony.platform.tapir.dsl.ServerEndpoints

/**
 * Wires the search module: the Solr-backed search service and indexer, the search endpoints, and the [[ResourceIndex]]
 * adapter consumed by the resources module.
 */
class SearchModule(config: SearchConfig, val searchService: SearchService, val indexer: SearchIndexer):

  val resourceIndex: ResourceIndex = new SearchResourceIndex(searchService, indexer)

  def routes(buckets: BucketRegistry)(using apiSecurity: ApiSecurity): ServerEndpoints[IO] =
    SearchRoutes.apply(searchService, config, buckets)

object SearchModule:

  def resource(config: SearchConfig): Resource[IO, SearchModule] =
    SolrResource.make(config.solr).map { solr =>
      new SearchModule(config, new SolrSearchService(config.solr, solr), new SolrIndexer(config.solr, solr))
    }
