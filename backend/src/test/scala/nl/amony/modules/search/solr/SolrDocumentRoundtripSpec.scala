package nl.amony.modules.search.solr

import scala.jdk.CollectionConverters.*

import org.apache.solr.common.{SolrDocument, SolrInputDocument}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*

class SolrDocumentRoundtripSpec extends AnyWordSpecLike with Matchers {

  private val indexer = SolrIndexer(SolrConfig(path = "", commitWithinMillis = 0), null)
  private val search  = SolrSearchService(SolrConfig(path = "", commitWithinMillis = 0), null)

  private def toSolrDocument(input: SolrInputDocument): SolrDocument = {
    val document = new SolrDocument()
    input.keySet().asScala.foreach { field =>
      val values = input.getField(field).getValues
      if values.size() == 1 then document.setField(field, values.iterator().next())
      else document.setField(field, new java.util.ArrayList(values))
    }
    document
  }

  "Solr document mapping" should {

    "preserve every field contentVersion depends on" in {
      val resource = ResourceInfo(
        bucketId           = BucketId("media"),
        resourceId         = ResourceId("resource-id"),
        userId             = UserId("user-id"),
        path               = "videos/clip.mp4",
        size               = 123456L,
        partialHash        = Some("partial-hash"),
        fileKey            = Some("(dev=1,ino=2)"),
        contentType        = Some("video/mp4"),
        contentMeta        = Some(ResourceMeta("ffmpeg", "{}", VideoProperties(1920, 1080, 30.0f, 9000, Some("h264")))),
        timeAdded          = Some(1000L),
        timeLastModified   = Some(2000L),
        title              = Some("Clip"),
        thumbnailTimestamp = Some(3000),
        streamable         = Some(true),
        tags               = Set("tag-a", "tag-b")
      )

      val roundtripped = search.toResource(toSolrDocument(indexer.toSolrDocument(resource)))

      roundtripped.partialHash shouldBe resource.partialHash
      roundtripped.fileKey shouldBe resource.fileKey
      roundtripped.size shouldBe resource.size
      roundtripped.timeLastModified shouldBe resource.timeLastModified
      ResourceOperations.contentVersion(roundtripped) shouldBe ResourceOperations.contentVersion(resource)
    }
  }
}
