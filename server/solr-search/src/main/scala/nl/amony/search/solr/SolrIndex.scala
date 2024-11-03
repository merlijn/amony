package nl.amony.search.solr

import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.*
import nl.amony.service.search.api.Query
import nl.amony.service.search.api.SortDirection.Desc
import nl.amony.service.search.api.SortField.*
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.client.solrj.{SolrClient, SolrQuery}
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import org.apache.solr.core.CoreContainer
import scribe.Logging

import java.nio.file.Path
import java.util.Properties
import scala.jdk.CollectionConverters.*

class SolrIndex(solrHome: Path) extends Logging {

  System.getProperties.setProperty("solr.data.dir", solrHome.toAbsolutePath.toString)

  val container = new CoreContainer(solrHome, new Properties())
  container.load()
  val solr: SolrClient = new EmbeddedSolrServer(container, "amony_embedded")

  def insert(media: ResourceInfo): Unit = {

    val fileNameOrTitle = media.title.getOrElse(media.path).split('/').last
    val title = fileNameOrTitle.substring(0, fileNameOrTitle.lastIndexOf('.'))
    logger.info(s"Indexing media: $title")

    val solrInputDocument: SolrInputDocument = new SolrInputDocument()
    solrInputDocument.addField("id", media.hash)
    solrInputDocument.addField("title_s", title)
    solrInputDocument.addField("lastmodified_l", media.lastModifiedTime.getOrElse(0L))
    solrInputDocument.addField("created_l", media.creationTime.getOrElse(0L))
    solrInputDocument.addField("filesize_l", media.size)
    solrInputDocument.addField("tags_ss", media.tags.toList.asJava)
//    solrInputDocument.addField("duration_l", media.videoInfo.duration)
//    solrInputDocument.addField("fps_d", media.videoInfo.fps)
//    solrInputDocument.addField("width_i", media.width)
//    solrInputDocument.addField("height_i", media.videoInfo.resolution._2)

    try {
      solr.add("amony_embedded", solrInputDocument).getStatus
    }
    catch {
      case e: Exception =>
        logger.warn("Exception while trying to index document to solr", e)
    }
  }

  def total() = {
    // Create a Solr query with no filters
    val query = new SolrQuery("*:*")
    query.setRows(0) // We only want the count, not the actual documents

    // Execute the query
    val response = solr.query("amony_embedded", query)

    // Get the number of documents
    val n  = response.getResults.getNumFound

    logger.info(s"Total number of documents in Solr: $n")
  }

  def search(query: Query) = {
    logger.info(s"Query: $query")

    val q = query.q.getOrElse("")

    val solrParams = new ModifiableSolrParams
    solrParams.add(CommonParams.Q, s"title_s:*${if (q.trim.isEmpty) "" else s"$q*"}")
    solrParams.add(CommonParams.START, query.offset.getOrElse(0).toString)
    solrParams.add(CommonParams.ROWS, query.n.toString)

    val sort: String = query.sort.map { option =>
      val solrField = option.field match {
        case Title => "title_s"
        case DateAdded => "created_l"
        case Size => "filesize_l"
        case Duration => "duration_l"
      }

      s"$solrField ${if (option.direction == Desc) "desc" else "asc"}"
    }.getOrElse("created_l desc")

    solrParams.add(CommonParams.SORT, sort)

    val queryResponse = solr.query(solrParams)

    val ids = queryResponse.getResults.asScala.map(_.getFieldValue("id").asInstanceOf[String])
    val total = queryResponse.getResults.getNumFound
    val offset = queryResponse.getResults.getStart

    logger.info(s"number found: ${queryResponse.getResults.getNumFound}")
  }

  def index(event: ResourceEvent): Unit = event match {

    case ResourceAdded(media) => insert(media)
    case _ =>
      logger.info(s"Ignoring event: $event")
      ()
  }

}
