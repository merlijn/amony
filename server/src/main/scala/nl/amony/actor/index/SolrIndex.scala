package nl.amony.actor.index

import akka.actor.Actor
import nl.amony.App.{appConfig, logger}
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibEventSourcing
import nl.amony.actor.MediaLibEventSourcing.{MediaAdded, MediaMetaDataUpdated, MediaUpdated}
import nl.amony.actor.MediaLibProtocol.Media
import nl.amony.actor.index.QueryProtocol.{GetPlaylists, GetTags, Search, SearchResult}
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import scribe.Logging

import java.nio.file.Path
import java.util.Properties
import scala.jdk.CollectionConverters.IterableHasAsScala

object SolrIndex extends Logging {

  def startSolr() = {
    import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
    import org.apache.solr.core.CoreContainer

    val solrPath = Path.of("./solr")

    logger.info(s"solr path: ${solrPath.toAbsolutePath.toString}")

    val properties = new Properties()
    properties.setProperty("data.dir", appConfig.media.indexPath.resolve("solr").toAbsolutePath.toString)
    properties.setProperty("solr.data.dir", appConfig.media.indexPath.resolve("solr").toAbsolutePath.toString)

    val container = new CoreContainer(solrPath, properties)
    container.load()
    val server = new EmbeddedSolrServer(container, "amony_embedded")
    server
  }

  class SolrIndexActor(config: MediaLibConfig) extends Actor with Logging {

    val solr = startSolr()

    def addMediaToSolr(media: Media): Unit = {
      val solrInputDocument: SolrInputDocument = new SolrInputDocument()
      solrInputDocument.addField("id", media.id)
      solrInputDocument.addField("title_s", media.title.getOrElse(media.fileName()))

      try {
        solr.add("amony_embedded", solrInputDocument).getStatus
      }
      catch {
        case e: Exception =>
          logger.warn("Exception while trying to index document to solr", e)
      }
    }

    override def receive: Receive = {

      case MediaAdded(media) =>
        addMediaToSolr(media)

      case MediaUpdated(id, media) =>
        addMediaToSolr(media)

      case MediaMetaDataUpdated(id, title, comment, tagsAdded, tagsRemoved) =>
        ()

      case GetPlaylists(sender) =>
        sender.tell(List.empty)

      case GetTags(sender) =>
        sender.tell(Set.empty)

      case Search(query, sender) =>

        logger.info(s"Query: $query")

        val solrParams = new ModifiableSolrParams
        solrParams.add(CommonParams.Q, "*:*")
        solrParams.add(CommonParams.START, query.offset.getOrElse(0).toString)
        solrParams.add(CommonParams.ROWS, query.n.toString)

        val queryResponse = solr.query(solrParams)

        logger.info(s"Result size: ${queryResponse.getResults.size()}")

        for (document <- queryResponse.getResults.asScala) {
          println(document)
        }

        sender.tell(SearchResult(query.offset.getOrElse(0), 0, List.empty, Map.empty))
    }
  }

}
