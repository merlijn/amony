package nl.amony.modules.search

import cats.effect.{IO, Resource}

import nl.amony.lib.tapir.dsl.ServerEndpoints
import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.resources.api.BucketRegistry
import nl.amony.modules.search.api.{SearchIndexer, SearchService}
import nl.amony.modules.search.http.SearchRoutes
import nl.amony.modules.search.solr.{SolrIndexer, SolrResource, SolrSearchService}

/**
 * Wires the search module: the Solr-backed search service and indexer, and the search endpoints. Both services
 * share a single Solr client. The bucket registry is passed to [[routes]] rather than into the module, because
 * the resources module needs the search service to build its own bucket registry.
 */
class SearchModule(config: SearchConfig, val searchService: SearchService, val indexer: SearchIndexer):

  def routes(buckets: BucketRegistry)(using apiSecurity: ApiSecurity): ServerEndpoints[IO] =
    SearchRoutes.apply(searchService, config, buckets)

object SearchModule:

  def resource(config: SearchConfig): Resource[IO, SearchModule] =
    SolrResource.make(config.solr).map { solr =>
      new SearchModule(config, new SolrSearchService(config.solr, solr), new SolrIndexer(config.solr, solr))
    }
