package nl.amony.modules.search.solr

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import fs2.Chunk
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.common.SolrInputDocument

import nl.amony.modules.resources.api.*
import nl.amony.modules.search.api.SearchIndexer
import nl.amony.modules.search.solr.SolrSchema.*

/** Writes resource changes to the Solr collection. */
class SolrIndexer(config: SolrConfig, solr: SolrClient) extends SearchIndexer with SolrLogging {

  private def toSolrDocument(resource: ResourceInfo): SolrInputDocument = {

    val solrInputDocument: SolrInputDocument = new SolrInputDocument()

    solrInputDocument.addField(FieldNames.id, resource.resourceId)
    solrInputDocument.addField(FieldNames.bucketId, resource.bucketId)
    solrInputDocument.addField(FieldNames.userId, resource.userId)
    solrInputDocument.addField(FieldNames.path, resource.path)
    solrInputDocument.addField(FieldNames.filesize, resource.size)

    val maybeTags = Option.when(resource.tags.nonEmpty)(resource.tags)
    maybeTags.foreach(tags => solrInputDocument.addField(FieldNames.tags, tags.toList.asJava))

    resource.thumbnailTimestamp.foreach(timestamp => solrInputDocument.addField(FieldNames.thumbnailTimestamp, timestamp))
    resource.title.foreach(title => solrInputDocument.addField(FieldNames.title, title))
    resource.description.foreach(description => solrInputDocument.addField(FieldNames.description, description))
    resource.timeAdded.foreach(created => solrInputDocument.addField(FieldNames.timeAdded, created))
    resource.timeLastModified.foreach(lastModified => solrInputDocument.addField(FieldNames.lastModified, lastModified))
    resource.contentType.foreach(contentType => solrInputDocument.addField(FieldNames.contentType, contentType))
    resource.streamable.foreach(streamable => solrInputDocument.addField(FieldNames.streamable, if streamable then "true" else "false"))

    resource.contentMeta.foreach(meta => solrInputDocument.addField(FieldNames.metaToolName, meta.toolName))

    resource.basicContentProperties match {
      case Some(ImageProperties(w, h, _))                    =>
        solrInputDocument.addField(FieldNames.width, w)
        solrInputDocument.addField(FieldNames.height, h)
        solrInputDocument.addField(FieldNames.resourceType, "image")
      case Some(VideoProperties(w, h, fps, duration, codec)) =>
        solrInputDocument.addField(FieldNames.width, w)
        solrInputDocument.addField(FieldNames.height, h)
        solrInputDocument.addField(FieldNames.duration, duration)
        codec.foreach(codec => solrInputDocument.addField(FieldNames.videoCodec, codec))
        solrInputDocument.addField(FieldNames.fps, fps)
        solrInputDocument.addField(FieldNames.resourceType, "video")
      case _                                                 =>
    }

    logger.debug(s"Indexing document: $solrInputDocument")

    solrInputDocument
  }

  private def insert(resource: ResourceInfo, commitWithinMs: Int = config.commitWithinMillis) =
    try {
      logger.debug(s"Indexing media: ${resource.path}")
      val solrInputDocument = toSolrDocument(resource)
      solr.add(collectionName, solrInputDocument, commitWithinMs).getStatus
    } catch { case e: Exception => logger.error("Exception while trying to index document to solr", e) }

  private def insertAll(resources: Chunk[ResourceInfo], commitWithinMs: Int = config.commitWithinMillis) =
    try {
      logger.debug(s"Indexing batch of media, size: ${resources.size}")
      val solrInputDocuments = resources.map(toSolrDocument).asJava
      solr.add(collectionName, solrInputDocuments, commitWithinMs).getStatus
    } catch { case e: Exception => logger.error("Exception while trying to index documents to solr", e) }

  override def processEvent(event: ResourceEvent): IO[Unit] =
    logger.debug(s"Processing event: $event")

    def atomicUpdate(resourceId: ResourceId, field: String, value: Any): Unit =
      val solrDocument = new SolrInputDocument()
      solrDocument.addField(FieldNames.id, resourceId)
      solrDocument.addField(field, Map("set" -> value).asJava)
      solr.add(collectionName, solrDocument, config.commitWithinMillis).getStatus

    def insertDocument(resource: ResourceInfo): Unit =
      logger.debug(s"Indexing media: ${resource.path}")
      solr.add(collectionName, toSolrDocument(resource), config.commitWithinMillis).getStatus

    event match
      case ResourceAdded(resource)                       => loggingFailureIO(insertDocument(resource))
      case ResourceUpdated(resource)                     => loggingFailureIO(insertDocument(resource))
      case ResourceMoved(resourceId, _, newPath)         => loggingFailureIO(atomicUpdate(resourceId, FieldNames.path, newPath))
      case ResourceFileMetaChanged(id, lastModifiedTime) => loggingFailureIO(atomicUpdate(id, FieldNames.lastModified, lastModifiedTime))
      case ResourceDeleted(resourceId)                   => loggingFailureIO(solr.deleteById(collectionName, resourceId, config.commitWithinMillis).getStatus)
      case BucketDeleted(bucketId)                       => deleteBucket(bucketId)

  override def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    resources
      .chunkN(100)
      .evalMap(resources => IO.blocking(insertAll(resources, commitWithinMs = 60000)))
      .compile.drain
      .handleErrorWith(t => IO(logger.error("Error while re-indexing", t)) >> IO.raiseError(t))

  override def index(resource: ResourceInfo): IO[Unit] = IO(insert(resource))

  override def forceCommit(): IO[Unit] =
    loggingFailureIO {
      logger.info("Forcing commit")
      solr.commit(collectionName)
    }

  override def deleteBucket(bucketId: BucketId): IO[Unit] =
    loggingFailureIO {
      logger.info(s"Deleting bucket: $bucketId")
      solr.deleteByQuery(collectionName, s"${FieldNames.bucketId}:${ClientUtils.escapeQueryChars(bucketId)}", config.commitWithinMillis)
      solr.commit(collectionName)
    }
}
