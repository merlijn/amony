package nl.amony.modules.search.solr

import org.apache.solr.common.SolrDocument
import org.apache.solr.common.params.CommonParams
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.search.SolrConfig
import nl.amony.modules.search.api.{ContentTypeGroup, Query}

class SolrSearchServiceSpec extends AnyWordSpecLike with Matchers {

  private val search = SolrSearchService(SolrConfig(path = "", commitWithinMillis = 0), null)

  "SolrSearchService" when {

    "reading a resource" should {
      "reject documents without a partial hash" in {
        val document = new SolrDocument()
        document.setField(SolrSchema.FieldNames.id, "resource-id")

        val error = intercept[IllegalArgumentException](search.toResource(document))
        error.getMessage shouldBe "Solr resource 'resource-id' is missing field 'partial_hash_s'"
      }
    }

    "searching by text query" should {

      "match both path and title with an OR when a query is given" in {
        val query = search.toSolrQuery(Query(n = 1, q = Some("holiday"))).get(CommonParams.Q)

        query shouldBe s"(${SolrSchema.FieldNames.path}:*holiday* OR ${SolrSchema.FieldNames.title}:*holiday*)"
      }

      "match all documents when no query is given" in {
        val query = search.toSolrQuery(Query(n = 1, q = None)).get(CommonParams.Q)

        query shouldBe s"${SolrSchema.FieldNames.path}:*"
      }
    }

    "filtering on thumbnails" should {

      "only return videos without a thumbnail timestamp when noThumbnail is set" in {
        val query = search.toSolrQuery(Query(n = 1, noThumbnail = Some(true))).get(CommonParams.Q)

        query should include("resource_type_s:video")
        query should include("-thumbnailtimestamp_i:[* TO *]")
      }

      "not filter on thumbnail timestamps by default" in {
        search.toSolrQuery(Query(n = 1)).get(CommonParams.Q) should not include "thumbnailtimestamp_i"
      }
    }

    "filtering on content types" should {

      "filter on a content type prefix when a content type group is selected" in {
        val query = search.toSolrQuery(Query(n = 1, contentTypes = Set(ContentTypeGroup.Image))).get(CommonParams.Q)

        query should include("content_type_s:image*")
        query should not include "content_type_s:video*"
      }

      "combine selected content type groups with an OR" in {
        val query = search.toSolrQuery(Query(n = 1, contentTypes = Set(ContentTypeGroup.Image, ContentTypeGroup.Video))).get(CommonParams.Q)

        query should include("(content_type_s:image* OR content_type_s:video*)")
      }

      "treat other as everything that is neither image nor video" in {
        val query = search.toSolrQuery(Query(n = 1, contentTypes = Set(ContentTypeGroup.Other))).get(CommonParams.Q)

        query should include("(-content_type_s:image* AND -content_type_s:video*)")
      }

      "not filter on content type when all groups are selected" in {
        search.toSolrQuery(Query(n = 1, contentTypes = ContentTypeGroup.values.toSet)).get(CommonParams.Q) should not include "content_type_s"
      }
    }
  }
}
