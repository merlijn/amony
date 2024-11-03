package nl.amony.search.solr

import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.*
import nl.amony.service.search.api.Query
import nl.amony.service.search.api.SortDirection.Desc
import nl.amony.service.search.api.SortField.*
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import scribe.Logging
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.core.CoreContainer

import java.nio.file.Path
import java.util.Properties
import scala.util.{Failure, Success}
import scala.jdk.CollectionConverters.*

class SolrIndex(solrHome: Path) extends Logging {

  System.getProperties.setProperty("solr.data.dir", solrHome.toAbsolutePath.toString)

  val container = new CoreContainer(solrHome, new Properties())
  container.load()
  val solr = new EmbeddedSolrServer(container, "amony_embedded")
  
  def insert(media: ResourceInfo): Unit = {

    val solrInputDocument: SolrInputDocument = new SolrInputDocument()
    solrInputDocument.addField("id", media.hash)
    solrInputDocument.addField("title_s", media.title.getOrElse(media.path))
    solrInputDocument.addField("lastmodified_l", media.lastModifiedTime)
    solrInputDocument.addField("created_l", media.creationTime)
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
