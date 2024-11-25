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
import org.apache.solr.common.params.{CommonParams, FacetParams, ModifiableSolrParams}
import org.apache.solr.common.{SolrDocument, SolrInputDocument}
import org.apache.solr.core.CoreContainer
import scribe.Logging

import java.nio.file.{Files, Path}
import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import SolrIndex.*

object SolrIndex {

  val collectionName = "amony_embedded"
  val defaultSort = SortOption(DateAdded, Desc)
  val tagsLimit = 12.toString
  val commitWithinMillis = 5000
  val solrTarGzResource = "/solr.tar.gz"
  
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
    val fps = "fps_f"
    val resourceType = "resource_type_s"
  }
}

class SolrIndex(config: SolrConfig)(using ec: ExecutionContext) extends SearchService with Logging {

  private val solrHome: Path = Path.of(config.path).toAbsolutePath.normalize()

  logger.info(s"Solr home: $solrHome")

  if (Files.exists(solrHome) && !Files.isDirectory(solrHome))
    throw new RuntimeException(s"Solr home is not a directory: $solrHome")

  if (!Files.exists(solrHome)) {
    logger.info(s"Solr directory does not exists. Creating it at: $solrHome")
    TarGzExtractor.extractResourceTarGz(solrTarGzResource, solrHome)
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
  
  private val container = new CoreContainer(solrHome, new Properties())
  container.load()
  private val solr: SolrClient = new EmbeddedSolrServer(container, collectionName)

  private def toSolrDocument(resource: ResourceInfo): SolrInputDocument = {
    
    val solrInputDocument: SolrInputDocument = new SolrInputDocument()
    solrInputDocument.addField(FieldNames.id, resource.hash)
    solrInputDocument.addField(FieldNames.bucketId, resource.bucketId)
    solrInputDocument.addField(FieldNames.path, resource.path)
    solrInputDocument.addField(FieldNames.filesize, resource.size)

    val maybeTags = Option.when(resource.tags.nonEmpty)(resource.tags)
    maybeTags.foreach(tags => solrInputDocument.addField(FieldNames.tags, resource.tags.toList.asJava))

    resource.thumbnailTimestamp.foreach(timestamp => solrInputDocument.addField(FieldNames.thumbnailTimestamp, timestamp))
    resource.title.foreach(title => solrInputDocument.addField(FieldNames.title, title))
    resource.description.foreach(description => solrInputDocument.addField(FieldNames.description, description))
    resource.creationTime.foreach(created => solrInputDocument.addField(FieldNames.created, created))
    resource.lastModifiedTime.foreach(lastModified => solrInputDocument.addField(FieldNames.lastModified, lastModified))
    resource.contentType.foreach(contentType => solrInputDocument.addField(FieldNames.contentType, contentType))

    resource.contentMeta match {
      case ImageMeta(w, h, _) =>
        solrInputDocument.addField(FieldNames.width, w)
        solrInputDocument.addField(FieldNames.height, h)
        solrInputDocument.addField(FieldNames.resourceType, "image")
      case VideoMeta(w, h, fps, duration, _) =>
        solrInputDocument.addField(FieldNames.width, w)
        solrInputDocument.addField(FieldNames.height, h)
        solrInputDocument.addField(FieldNames.duration, duration)
        solrInputDocument.addField(FieldNames.fps, fps)
        solrInputDocument.addField(FieldNames.resourceType, "video")
      case _ =>
    }

    solrInputDocument
  }

  private def toResource(document: SolrDocument): ResourceInfo = {

    val id = document.getFieldValue(FieldNames.id).asInstanceOf[String]
    val bucketId = document.getFieldValue(FieldNames.bucketId).asInstanceOf[String]
    val title = Option(document.getFieldValue(FieldNames.title)).map(_.asInstanceOf[String])
    val path = document.getFieldValue(FieldNames.path).asInstanceOf[String]
    val creationTime = Option(document.getFieldValue(FieldNames.created)).map(_.asInstanceOf[Long])
    val lastModified = Option(document.getFieldValue(FieldNames.lastModified)).map(_.asInstanceOf[Long])
    val size = document.getFieldValue(FieldNames.filesize).asInstanceOf[Long]

    val contentType = Option(document.getFieldValue(FieldNames.contentType)).map(_.asInstanceOf[String])

    val width = document.getFieldValue(FieldNames.width).asInstanceOf[Int]
    val height = document.getFieldValue(FieldNames.height).asInstanceOf[Int]
    val description = Option(document.getFieldValue(FieldNames.description)).map(_.asInstanceOf[String])

    val resourceType = document.getFieldValue(FieldNames.resourceType).asInstanceOf[String]
    val thumbnailTimestamp = Option(document.getFieldValue(FieldNames.thumbnailTimestamp)).map(_.asInstanceOf[Long])

    val tags = Option(document.getFieldValues(FieldNames.tags)).map(_.asInstanceOf[java.util.List[String]].asScala).getOrElse(List.empty).toSeq

    val contentMeta: ResourceMeta = resourceType match {

      case "image" => ImageMeta(width, height)
      case "video" =>
        val duration = document.getFieldValue(FieldNames.duration).asInstanceOf[Long]
        val fps = document.getFieldValue(FieldNames.fps).asInstanceOf[Float]
        VideoMeta(width, height, fps, duration, Map.empty)
      case _ => ResourceMeta.Empty
    }

    ResourceInfo(bucketId, path, id, size, contentType, contentMeta, creationTime, lastModified, title, description, tags, thumbnailTimestamp)
  }

  private def toSolrQuery(query: Query) = {
    
    val sort = query.sort.getOrElse(defaultSort)

    val solrSort = {

      val solrField = sort.field match
        case Title     => FieldNames.title
        case DateAdded => FieldNames.created
        case Size      => FieldNames.filesize
        case Duration  => FieldNames.duration
        case _         => FieldNames.created

      val direction = if (sort.direction == Desc) "desc" else "asc"

      // TODO add random sort feature
      // https://ubuntuask.com/blog/how-to-boost-fields-with-random-sort-in-solr
      // random_1234 desc

      s"$solrField $direction"
    }

    val solrParams = new ModifiableSolrParams

    val solrQ = {
      val q = query.q.getOrElse("")
      val sb = new StringBuilder()

      sb.append(s"${FieldNames.path}:*${if (q.trim.isEmpty) "" else s"$q*"}")
      if(query.tags.nonEmpty)
        sb.append(s" AND ${FieldNames.tags}:(${query.tags.mkString(" OR ")})")
      
      if (query.minRes.isDefined || query.maxRes.isDefined)
        sb.append(s" AND ${FieldNames.width}:[${query.minRes.getOrElse(0)} TO ${query.maxRes.getOrElse("*")}]")
        
      if (query.minDuration.isDefined || query.maxDuration.isDefined)
        sb.append(s" AND ${FieldNames.duration}:[${query.minDuration.getOrElse(0)} TO ${query.maxDuration.getOrElse("*")}]")  
      
      sb.result()
    }

    solrParams.add(CommonParams.Q, solrQ)
    solrParams.add(CommonParams.START, query.offset.getOrElse(0).toString)
    solrParams.add(CommonParams.ROWS, query.n.toString)
    solrParams.add(CommonParams.SORT, solrSort)
    solrParams.add(FacetParams.FACET, "true")
    solrParams.add(FacetParams.FACET_FIELD, FieldNames.tags)
    solrParams.add(FacetParams.FACET_LIMIT, tagsLimit)

    solrParams
  }

  def totalDocuments(bucketId: String): Long =
    solr.query(collectionName, new SolrQuery(s"bucket_id_s:$bucketId")).getResults.getNumFound

  private def insertDocument(resource: ResourceInfo) = {
    try {
      logger.debug(s"Indexing media: ${resource.path}")
      val solrInputDocument = toSolrDocument(resource)
      solr.add(collectionName, solrInputDocument, commitWithinMillis).getStatus
    }
    catch {
      case e: Exception => logger.error("Exception while trying to index document to solr", e)
    }
  }

  def processEvent(event: ResourceEvent): Unit = event match {

    case ResourceAdded(resource)   => insertDocument(resource)
    case ResourceUpdated(resource) => insertDocument(resource)

    case ResourceMoved(resourceId, oldPath, newPath) =>
      val solrDocument = new SolrInputDocument()
      solrDocument.addField(FieldNames.id, resourceId)
      solrDocument.addField(FieldNames.path, Map("set" -> newPath).asJava)
      solr.add(collectionName, solrDocument, commitWithinMillis).getStatus

    case ResourceDeleted(resourceId) =>
      try {
        logger.debug(s"Deleting document from index: $resourceId")
        solr.deleteById(collectionName, resourceId, commitWithinMillis).getStatus
      }
      catch {
        case e: Exception => logger.error("Exception while trying to delete document from solr", e)
      }
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

      val tagsWithFrequency: Map[String, Long] = Option(queryResponse.getFacetFields)
        .flatMap(fields => Try(fields.get(0)).toOption)
        .map { results => 
          results.getValues.iterator().asScala.foldLeft(Map.empty[String, Long]) {
            case (acc, value) if value.getCount > 0 => acc + (value.getName -> value.getCount)
            case (acc, _) => acc
          }
        }.getOrElse(Map.empty[String, Long])

      logger.debug(s"Search query: ${solrParams.toString}, total: $total, tags: ${tagsWithFrequency.mkString(", ")}")

      SearchResult(
        offset = offset.toInt,
        total = total.toInt,
        results = results.asScala.map(toResource).toList,
        tags = tagsWithFrequency
      )
    }
  }
}
