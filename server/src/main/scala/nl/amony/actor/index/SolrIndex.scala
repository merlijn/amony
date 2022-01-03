package nl.amony.actor.index

import akka.actor.Actor
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import nl.amony.App.appConfig
import nl.amony.actor.MediaLibEventSourcing.{MediaAdded, MediaMetaDataUpdated, MediaUpdated}
import nl.amony.actor.MediaLibProtocol.{Command, GetByIds, Media}
import nl.amony.actor.index.QueryProtocol.{GetPlaylists, GetTags, Search, SearchResult}
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import scribe.Logging

import java.nio.file.Path
import java.util.Properties
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.{IterableHasAsScala, SeqHasAsJava}
import scala.util.{Failure, Success}

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

  class SolrIndexActor(media: ActorRef[Command]) extends Actor with Logging {

    val solr = startSolr()


    def addMediaToSolr(media: Media): Unit = {

      val solrInputDocument: SolrInputDocument = new SolrInputDocument()
      solrInputDocument.addField("id", media.id)
      solrInputDocument.addField("title_s", media.title.getOrElse(media.fileName()))
      solrInputDocument.addField("lastmodified_l", media.fileInfo.lastModifiedTime)
      solrInputDocument.addField("created_l", media.fileInfo.creationTime)
      solrInputDocument.addField("filesize_l", media.fileInfo.size)
      solrInputDocument.addField("tags_ss", media.tags.toList.asJava)
      solrInputDocument.addField("duration_l", media.videoInfo.duration)
      solrInputDocument.addField("fps_d", media.videoInfo.fps)
      solrInputDocument.addField("width_i", media.videoInfo.resolution._1)
      solrInputDocument.addField("height_i", media.videoInfo.resolution._2)

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

        val q = query.q.getOrElse("")
        val solrParams = new ModifiableSolrParams
        solrParams.add(CommonParams.Q, s"title_s:*${if (q.trim.isEmpty) "" else s"$q*"}")
        solrParams.add(CommonParams.START, query.offset.getOrElse(0).toString)
        solrParams.add(CommonParams.ROWS, query.n.toString)

        val queryResponse = solr.query(solrParams)

        val ids = queryResponse.getResults.asScala.map(_.getFieldValue("id").asInstanceOf[String]).toSet
        val total = queryResponse.getResults.getNumFound
        val offset = queryResponse.getResults.getStart

        logger.info(s"number found: ${queryResponse.getResults.getNumFound}")

        media.ask[Map[String, Media]](ref => GetByIds(ids, ref))(Timeout(5.seconds), context.system.toTyped.scheduler).onComplete {
          case Success(results) =>
            val medias = results.values.toSeq
            sender.tell(SearchResult(offset, total, medias, Map.empty))
          case Failure(e) =>
            sender.tell(SearchResult(offset, total, List.empty, Map.empty))
        }(context.dispatcher)
    }
  }

}
