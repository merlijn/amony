package nl.amony.modules.search.solr

import org.apache.solr.common.params.CommonParams
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.search.api.Query

class SolrSearchQuerySpec extends AnyWordSpecLike with Matchers {

  private val search = SolrSearchService(SolrConfig(path = "", commitWithinMillis = 0), null)

  "Solr query mapping" should {

    "only return videos without a thumbnail timestamp when noThumbnail is set" in {
      val query = search.toSolrQuery(Query(n = 1, noThumbnail = Some(true))).get(CommonParams.Q)

      query should include("resource_type_s:video")
      query should include("-thumbnailtimestamp_i:[* TO *]")
    }

    "not filter on thumbnail timestamps by default" in {
      search.toSolrQuery(Query(n = 1)).get(CommonParams.Q) should not include "thumbnailtimestamp_i"
    }
  }
}
