package nl.amony.service.search.solr

import cats.effect.{IO, Resource}
import SolrSearchService.*
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.Desc
import nl.amony.service.search.api.SortField.*
import nl.amony.service.search.api.*
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.client.solrj.{SolrClient, SolrQuery}
import org.apache.solr.common.params.{CommonParams, FacetParams, ModifiableSolrParams}
import org.apache.solr.common.{SolrDocument, SolrInputDocument}
import org.apache.solr.core.CoreContainer
import org.apache.solr.client.solrj.util.ClientUtils
import scribe.Logging
import io.grpc.stub.StreamObserver

import java.nio.file.{Files, Path}
import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

object SolrSearchService {

  val collectionName = "amony_embedded"
  val defaultSort = SortOption(DateAdded, Desc)
  val tagsLimit = 12.toString
  val solrTarGzResource = "/solr.tar.gz"
  
  object FieldNames {
    val id = "id"
    val bucketId = "bucket_id_s"
    val hash = "hash_s"
    val path = "path_text_ci"
    val filesize = "filesize_l"
    val tags = "tags_ss"
    val thumbnailTimestamp = "thumbnailtimestamp_i"
    val title = "title_s"
    val videoCodec = "video_codec_s"
    val description = "description_s"
    val created = "created_l"
    val lastModified = "lastmodified_l"
    val contentType = "content_type_s"
    val width = "width_i"
    val height = "height_i"
    val duration = "duration_i"
    val fps = "fps_f"
    val resourceType = "resource_type_s"
    val userId = "user_id_s"
  }

  def resource(config: SolrConfig)(using ec: ExecutionContext): Resource[IO, SolrSearchService] =
    Resource.make[IO, SolrSearchService](IO(new SolrSearchService(config)))(_.close())
}

class SolrSearchService(config: SolrConfig)(using ec: ExecutionContext) extends SearchService with Logging {

  private val solrHome: Path = Path.of(config.path).toAbsolutePath.normalize()

  logger.info(s"Solr home: $solrHome")

  def loggingFailureFuture[T](f: => T): Future[T] = {
    Future(f).recover {
      case e: Exception =>
        logger.error("Error while executing solr query", e)
        throw e
    }
  }

  private val lockfilePath = solrHome.resolve("index/write.lock")

  // delete the lock file on shutdown
  sys.addShutdownHook {
    try {
      logger.warn("JVM shutdown hook: committing solr")
      solr.commit(collectionName)
    } catch {
      case e: Exception => logger.error("Error while closing solr", e)
    }
  }

  if (Files.exists(solrHome) && !Files.isDirectory(solrHome))
    throw new RuntimeException(s"Solr home is not a directory: $solrHome")

  if (!Files.exists(solrHome)) {
    logger.info(s"Solr directory does not exists. Creating it at: $solrHome")
    TarGzExtractor.extractResourceTarGz(solrTarGzResource, solrHome)
  }

  protected def close(): IO[Unit] = IO {
    solr.commit(collectionName)
    solr.close()
    container.shutdown()
  }

  System.getProperties.setProperty("solr.data.dir", solrHome.toAbsolutePath.toString)
  
  private val container = new CoreContainer(solrHome, new Properties())
  container.load()
  private val solr: SolrClient = new EmbeddedSolrServer(container, collectionName)

  private def toSolrDocument(resource: ResourceInfo): SolrInputDocument = {
    
    val solrInputDocument: SolrInputDocument = new SolrInputDocument()

    solrInputDocument.addField(FieldNames.id, resource.resourceId)
    solrInputDocument.addField(FieldNames.bucketId, resource.bucketId)
    solrInputDocument.addField(FieldNames.userId, resource.userId)
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
      case VideoMeta(w, h, fps, duration, codec, _) =>
        solrInputDocument.addField(FieldNames.width, w)
        solrInputDocument.addField(FieldNames.height, h)
        solrInputDocument.addField(FieldNames.duration, duration)
        codec.foreach(codec => solrInputDocument.addField(FieldNames.videoCodec, codec))
        solrInputDocument.addField(FieldNames.fps, fps)
        solrInputDocument.addField(FieldNames.resourceType, "video")
      case _ =>
    }

    logger.debug(s"Indexing document: $solrInputDocument")
    
    solrInputDocument
  }

  private def toResource(document: SolrDocument): ResourceInfo = {
    
    val resourceId = document.getFieldValue(FieldNames.id).asInstanceOf[String]
    val hash = Option(document.getFieldValue(FieldNames.hash)).map(_.asInstanceOf[String])
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
    val thumbnailTimestamp = Option(document.getFieldValue(FieldNames.thumbnailTimestamp)).map(_.asInstanceOf[Int])

    val tags = Option(document.getFieldValues(FieldNames.tags)).map(_.asInstanceOf[java.util.List[String]].asScala).getOrElse(List.empty).toSet

    val userId = Option(document.getFieldValue(FieldNames.userId)).map(_.asInstanceOf[String])

    val contentMeta: ResourceMeta = resourceType match {

      case "image" => ImageMeta(width, height)
      case "video" =>
        val duration = document.getFieldValue(FieldNames.duration).asInstanceOf[Int]
        val fps = document.getFieldValue(FieldNames.fps).asInstanceOf[Float]
        val codec = Option(document.getFieldValue(FieldNames.videoCodec)).map(_.asInstanceOf[String])
        VideoMeta(width, height, fps, duration, codec, Map.empty)
      case _ => ResourceMeta.Empty
    }

    ResourceInfo(bucketId, resourceId, userId.getOrElse(""), path, size, hash, contentType, None, contentMeta, creationTime, lastModified, title, description, tags, thumbnailTimestamp)
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
      val q = ClientUtils.escapeQueryChars(query.q.getOrElse(""))
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

  private def insertResource(resource: ResourceInfo) = {
    try {
      logger.debug(s"Indexing media: ${resource.path}")
      val solrInputDocument = toSolrDocument(resource)
      solr.add(collectionName, solrInputDocument, config.commitWithinMillis).getStatus
    }
    catch {
      case e: Exception => logger.error("Exception while trying to index document to solr", e)
    }
  }

  def processEvent(event: ResourceEvent): Unit = event match {

    case ResourceAdded(resource)   => insertResource(resource)

    case ResourceUpdated(resource) => insertResource(resource)

    case ResourceMoved(resourceId, oldPath, newPath) =>
      val solrDocument = new SolrInputDocument()
      solrDocument.addField(FieldNames.id, resourceId)
      solrDocument.addField(FieldNames.path, Map("set" -> newPath).asJava)
      solr.add(collectionName, solrDocument, config.commitWithinMillis).getStatus

    case ResourceFileMetaChanged(id, creationTime, lastModifiedTime) =>
      val solrDocument = new SolrInputDocument()
      solrDocument.addField(FieldNames.id, id)
      creationTime.foreach(time => solrDocument.addField(FieldNames.created, Map("set" -> time).asJava))
      lastModifiedTime.foreach(time => solrDocument.addField(FieldNames.lastModified, Map("set" -> time).asJava))
      solr.add(collectionName, solrDocument, config.commitWithinMillis).getStatus

    case ResourceDeleted(resourceId) =>
      try {
        logger.debug(s"Deleting document from index: $resourceId")
        solr.deleteById(collectionName, resourceId, config.commitWithinMillis).getStatus
      }
      catch {
        case e: Exception => logger.error("Exception while trying to delete document from solr", e)
      }
    case _ =>
      logger.info(s"Ignoring event: $event")
      ()
  }
  
  override def indexAll(responseObserver: StreamObserver[ReIndexResult]): StreamObserver[ResourceInfo] = {
    new StreamObserver[ResourceInfo] {
      override def onNext(value: ResourceInfo): Unit = 
        insertResource(value)

      override def onError(t: Throwable): Unit = 
        logger.error("Error while re-indexing", t)

      override def onCompleted(): Unit = 
        responseObserver.onNext(ReIndexResult())
        responseObserver.onCompleted()
    }
  }

  override def index(request: ResourceInfo): Future[ReIndexResult] = Future { insertResource(request); ReIndexResult() }

  override def searchMedia(query: Query): Future[SearchResult] = {

    loggingFailureFuture {

      val solrParams = toSolrQuery(query)
      val queryResponse = solr.query(solrParams)
      
      logger.debug(s"Executing solr query: ${solrParams.toString}")
      
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

      logger.debug(s"Solr response size: ${results.size()}, tags: ${tagsWithFrequency.mkString(", ")}")

      SearchResult(
        offset = offset.toInt,
        total = total.toInt,
        results = results.asScala.map(toResource).toList,
        tags = tagsWithFrequency
      )
    }
  }

  override def forceCommit(request: ForceCommitRequest): Future[ForceCommitResult] =
    loggingFailureFuture {
      logger.info("Forcing commit")
      solr.commit(collectionName); ForceCommitResult()
    }
}
