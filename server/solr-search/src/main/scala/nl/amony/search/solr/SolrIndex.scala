package nl.amony.search.solr

import nl.amony.search.solr.SolrIndex.FieldNames
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.Desc
import nl.amony.service.search.api.SortField.*
import nl.amony.service.search.api.{Query, SearchResult, SortOption}
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.client.solrj.{SolrClient, SolrQuery}
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import org.apache.solr.common.{SolrDocument, SolrInputDocument}
import org.apache.solr.core.CoreContainer
import scribe.Logging

import java.nio.file.{Files, Path}
import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

object SolrIndex {

  object FieldNames {
    val id = "id"
    val bucketId = "bucket_id_s"
    val path = "path_text_ci"
    val filesize = "filesize_l"
    val tags = "tags_ss"
    val thumbnailTimestamp = "thumbnailtimestamp_l"
    val title = "title_s"
    val description = "description_s"
    val created = "created_l"
    val lastModified = "lastmodified_l"
    val contentType = "content_type_s"
    val width = "width_i"
    val height = "height_i"
    val duration = "duration_l"
    val fps = "fps_d"
    val resourceType = "resource_type_s"
  }
}

class SolrIndex(config: SolrConfig)(implicit ec: ExecutionContext) extends SearchService with Logging {

  private val solrHome: Path = Path.of(config.path).toAbsolutePath.normalize()

  logger.info(s"Solr home: $solrHome")

  if (Files.exists(solrHome) && !Files.isDirectory(solrHome))
    throw new RuntimeException(s"Solr home is not a directory: $solrHome")

  if (!Files.exists(solrHome)) {
    logger.info(s"Solr directory does not exists. Creating it at: $solrHome")
    val parent = solrHome.getParent
    Files.createDirectories(parent) // create parent directories
    TarGzExtractor.extractResourceTarGz("/solr.tar.gz", parent) // the tarbal is in the resources
  }

  private val lockfilePath = solrHome.resolve("index/write.lock")

  if (Files.exists(lockfilePath) && config.deleteLockfileOnStartup) {
    logger.info(s"Deleting lock file at: $lockfilePath")
    Files.delete(lockfilePath)
  }

  // delete the lock file on shutdown
  sys.addShutdownHook {
    Files.delete(lockfilePath)
  }

  System.getProperties.setProperty("solr.data.dir", solrHome.toAbsolutePath.toString)

  private val collectionName = "amony_embedded"
  private val container = new CoreContainer(solrHome, new Properties())
  container.load()
  private val solr: SolrClient = new EmbeddedSolrServer(container, collectionName)

  private def toSolrDocument(resource: ResourceInfo): SolrInputDocument = {


    val solrInputDocument: SolrInputDocument = new SolrInputDocument()
    solrInputDocument.addField("id", resource.hash)
    solrInputDocument.addField("bucket_id_s", resource.bucketId)
    solrInputDocument.addField(FieldNames.path, resource.path)
    solrInputDocument.addField("filesize_l", resource.size)

    val maybeTags = Option.when(resource.tags.nonEmpty)(resource.tags)
    maybeTags.foreach(tags => solrInputDocument.addField("tags_ss", resource.tags.toList.asJava))

    resource.thumbnailTimestamp.foreach(timestamp => solrInputDocument.addField("thumbnailtimestamp_l", timestamp))
    resource.title.foreach(title => solrInputDocument.addField("title_s", title))
    resource.description.foreach(description => solrInputDocument.addField("description_s", description))
    resource.creationTime.foreach(created => solrInputDocument.addField("created_l", created))
    resource.lastModifiedTime.foreach(lastModified => solrInputDocument.addField("lastmodified_l", lastModified))
    resource.contentType.foreach(contentType => solrInputDocument.addField("content_type_s", contentType))

    resource.contentMeta match {
      case ImageMeta(w, h, _) =>
        solrInputDocument.addField("width_i", w)
        solrInputDocument.addField("height_i", h)
        solrInputDocument.addField("resource_type_s", "image")
      case VideoMeta(w, h, fps, duration, _) =>
        solrInputDocument.addField("width_i", w)
        solrInputDocument.addField("height_i", h)
        solrInputDocument.addField("duration_l", duration)
        solrInputDocument.addField("fps_d", fps.toDouble)
        solrInputDocument.addField("resource_type_s", "video")
      case _ =>
    }

    solrInputDocument
  }

  private def toResource(document: SolrDocument): ResourceInfo = {

    val id = document.getFieldValue("id").asInstanceOf[String]
    val bucketId = document.getFieldValue("bucket_id_s").asInstanceOf[String]
    val title = Option(document.getFieldValue("title_s")).map(_.asInstanceOf[String])
    val path = document.getFieldValue(FieldNames.path).asInstanceOf[String]
    val creationTime = Option(document.getFieldValue("created_l")).map(_.asInstanceOf[Long])
    val lastModified = Option(document.getFieldValue("lastmodified_l")).map(_.asInstanceOf[Long])
    val created = document.getFieldValue("created_l").asInstanceOf[Long]
    val size = document.getFieldValue("filesize_l").asInstanceOf[Long]

    val contentType = Option(document.getFieldValue("content_type_s")).map(_.asInstanceOf[String])

    val width = document.getFieldValue("width_i").asInstanceOf[Int]
    val height = document.getFieldValue("height_i").asInstanceOf[Int]
    val description = Option(document.getFieldValue("description_s")).map(_.asInstanceOf[String])

    val resourceType = document.getFieldValue("resource_type_s").asInstanceOf[String]
    val thumbnailTimestamp = Option(document.getFieldValue("thumbnailtimestamp_l")).map(_.asInstanceOf[Long])

    val tags = Option(document.getFieldValues("tags_ss")).map(_.asInstanceOf[java.util.List[String]].asScala).getOrElse(List.empty).toSeq

    val contentMeta: ResourceMeta = resourceType match {

      case "image" => ImageMeta(width, height)
      case "video" =>
        val duration = document.getFieldValue("duration_l").asInstanceOf[Long]
        val fps = document.getFieldValue("fps_f").asInstanceOf[Float]
        VideoMeta(width, height, fps, duration, Map.empty)
      case _ => ResourceMeta.Empty
    }

    ResourceInfo(bucketId, path, id, size, contentType, contentMeta, creationTime, lastModified, title, description, tags, thumbnailTimestamp)
  }

  private def toSolrQuery(query: Query) = {
    val q = query.q.getOrElse("")
    val defaultSort = SortOption(Title, Desc)
    val sort = query.sort.getOrElse(defaultSort)

    val solrSort = {

      val solrField = sort.field match
        case Title     => "title_s"
        case DateAdded => "created_l"
        case Size      => "filesize_l"
        case Duration  => "duration_l"
        case _         => "created_l"

      val direction = if (sort.direction == Desc) "desc" else "asc"

      s"$solrField $direction"
    }

    val solrParams = new ModifiableSolrParams
    solrParams.add(CommonParams.Q, s"${FieldNames.path}:*${if (q.trim.isEmpty) "" else s"$q*"}")
    solrParams.add(CommonParams.START, query.offset.getOrElse(0).toString)
    solrParams.add(CommonParams.ROWS, query.n.toString)
    solrParams.add(CommonParams.SORT, solrSort)
    solrParams
  }

  def totalDocuments(bucketId: String): Long =
    solr.query(collectionName, new SolrQuery(s"bucket_id_s:$bucketId")).getResults.getNumFound

  private def insertDocument(resource: ResourceInfo) = {
    try {
      logger.debug(s"Indexing media: ${resource.path}")
      val solrInputDocument = toSolrDocument(resource)
      solr.add(collectionName, solrInputDocument, 5000).getStatus
    }
    catch {
      case e: Exception => logger.error("Exception while trying to index document to solr", e)
    }
  }

  def index(event: ResourceEvent): Unit = event match {

    case ResourceAdded(resource)   => insertDocument(resource)
    case ResourceUpdated(resource) => insertDocument(resource)
    case _ =>
      logger.info(s"Ignoring event: $event")
      ()
  }

  override def searchMedia(query: Query): Future[SearchResult] = {

    Future {

      val solrParams = toSolrQuery(query)
      val queryResponse = solr.query(solrParams)
      val results = queryResponse.getResults

      val total = results.getNumFound
      val offset = results.getStart

      results.asScala.map(toResource).toList

      logger.debug(s"Search query: ${solrParams.toString}, total: $total")

      SearchResult(
        offset = offset.toInt,
        total = total.toInt,
        results = results.asScala.map(toResource).toList,
        tags = List.empty
      )
    }
  }
}
