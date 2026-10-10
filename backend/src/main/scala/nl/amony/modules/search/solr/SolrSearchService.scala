package nl.amony.modules.search.solr

import scala.jdk.CollectionConverters.*
import scala.util.Try

import cats.effect.IO
import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.client.solrj.{SolrClient, SolrQuery}
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.params.{CommonParams, FacetParams, ModifiableSolrParams}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.search.api.SortDirection.Desc
import nl.amony.modules.search.api.SortField.*
import nl.amony.modules.search.api.{Query, SearchResult, SearchService, SortOption}
import nl.amony.modules.search.solr.SolrSchema.*
import nl.amony.modules.search.solr.SolrSearchService.*

object SolrSearchService {
  val defaultSort = SortOption(DateAdded, Desc)
  val tagsLimit   = 12.toString
}

/** Queries the Solr collection. */
class SolrSearchService(config: SolrConfig, solr: SolrClient) extends SearchService with SolrLogging {

  private def toResource(document: SolrDocument): ResourceInfo = {

    val resourceId         = document.getFieldValue(FieldNames.id).asInstanceOf[String]
    val partialHash        = Option(document.getFieldValue(FieldNames.partialHash)).map(_.asInstanceOf[String])
    val bucketId           = document.getFieldValue(FieldNames.bucketId).asInstanceOf[String]
    val title              = Option(document.getFieldValue(FieldNames.title)).map(_.asInstanceOf[String])
    val path               = document.getFieldValue(FieldNames.path).asInstanceOf[String]
    val timeAdded          = Option(document.getFieldValue(FieldNames.timeAdded)).map(_.asInstanceOf[Long])
    val lastModified       = Option(document.getFieldValue(FieldNames.lastModified)).map(_.asInstanceOf[Long])
    val size               = document.getFieldValue(FieldNames.filesize).asInstanceOf[Long]
    val contentType        = Option(document.getFieldValue(FieldNames.contentType)).map(_.asInstanceOf[String])
    val width              = document.getFieldValue(FieldNames.width).asInstanceOf[Int]
    val height             = document.getFieldValue(FieldNames.height).asInstanceOf[Int]
    val description        = Option(document.getFieldValue(FieldNames.description)).map(_.asInstanceOf[String])
    val resourceType       = document.getFieldValue(FieldNames.resourceType).asInstanceOf[String]
    val thumbnailTimestamp = Option(document.getFieldValue(FieldNames.thumbnailTimestamp)).map(_.asInstanceOf[Int])
    val metaToolName       = Option(document.getFieldValue(FieldNames.metaToolName)).map(_.asInstanceOf[String])
    val tags               = Option(document.getFieldValues(FieldNames.tags)).map(_.asInstanceOf[java.util.List[String]].asScala).getOrElse(List.empty).toSet
    val userId             = document.getFieldValue(FieldNames.userId).asInstanceOf[String]
    val streamable         = Option(document.getFieldValue(FieldNames.streamable))
      .map(_.asInstanceOf[String])
      .flatMap {
        case "true"  => Some(true)
        case "false" => Some(false)
        case _       => None
      }

    val contentProperties: Option[ContentProperties] = resourceType match {

      case "image" => Some(ImageProperties(width, height))
      case "video" =>
        val duration = document.getFieldValue(FieldNames.duration).asInstanceOf[Int]
        val fps      = document.getFieldValue(FieldNames.fps).asInstanceOf[Float]
        val codec    = Option(document.getFieldValue(FieldNames.videoCodec)).map(_.asInstanceOf[String])
        Some(VideoProperties(width, height, fps, duration, codec))
      case _       => None
    }

    ResourceInfo(
      bucketId           = BucketId(bucketId),
      resourceId         = ResourceId(resourceId),
      userId             = UserId(userId),
      path               = path,
      size               = size,
      partialHash        = partialHash,
      contentType        = contentType,
      contentMeta        = contentProperties.map(props => ResourceMeta(metaToolName.getOrElse("unknown"), "", props)),
      timeAdded          = timeAdded,
      timeLastModified   = lastModified,
      title              = title,
      description        = description,
      tags               = tags,
      thumbnailTimestamp = thumbnailTimestamp,
      streamable         = streamable
    )
  }

  private def toSolrQuery(query: Query) = {

    val sort = query.sort.getOrElse(defaultSort)

    val solrSort = {

      val solrField = sort.field match
        case Title        => FieldNames.path
        case DateAdded    => FieldNames.timeAdded
        case Size         => FieldNames.filesize
        case Duration     => FieldNames.duration
        case Random(seed) => s"random_$seed"

      val direction = if sort.direction == Desc then "desc" else "asc"

      s"$solrField $direction"
    }

    val solrParams = new ModifiableSolrParams

    val solrQ = {
      val q  = ClientUtils.escapeQueryChars(query.q.getOrElse(""))
      val sb = new StringBuilder()

      sb.append(s"${FieldNames.path}:*${if q.trim.isEmpty then "" else s"$q*"}")

      if query.includeTags.nonEmpty then
        val escapedTags = query.includeTags.map(ClientUtils.escapeQueryChars)
        sb.append(s" AND ${FieldNames.tags}:(${escapedTags.mkString(" OR ")})")

      if query.excludeBuckets.nonEmpty then
        val escapedBuckets = query.excludeBuckets.map(ClientUtils.escapeQueryChars)
        sb.append(s" AND -${FieldNames.bucketId}:(${escapedBuckets.mkString(" OR ")})")

      if query.includeBuckets.nonEmpty then
        val escapedBuckets = query.includeBuckets.map(ClientUtils.escapeQueryChars)
        sb.append(s" AND ${FieldNames.bucketId}:(${escapedBuckets.mkString(" OR ")})")

      if query.untagged.contains(true) then sb.append(s" AND -${FieldNames.tags}:[* TO *]")

      query.streamable.foreach(streamable => sb.append(s" AND ${FieldNames.streamable}:$streamable"))

      if query.resolutionRange.min.isDefined || query.resolutionRange.max.isDefined then
        sb.append(s" AND ${FieldNames.width}:[${query.resolutionRange.min.getOrElse(0)} TO ${query.resolutionRange.max.getOrElse("*")}]")

      if query.durationRange.min.isDefined || query.durationRange.max.isDefined then
        sb.append(s" AND ${FieldNames.duration}:[${query.durationRange.min.getOrElse(0)} TO ${query.durationRange.max.getOrElse("*")}]")

      if query.uploadDateRange.min.isDefined || query.uploadDateRange.max.isDefined then
        sb.append(s" AND ${FieldNames.timeAdded}:[${query.uploadDateRange.min.getOrElse(0)} TO ${query.uploadDateRange.max.getOrElse("*")}]")

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

  def totalDocuments(bucketId: BucketId): Long = solr.query(collectionName, new SolrQuery(s"bucket_id_s:$bucketId")).getResults.getNumFound

  override def searchMedia(query: Query): IO[SearchResult] =

    loggingFailureIO {

      val solrParams    = toSolrQuery(query)
      val queryResponse = solr.query(collectionName, solrParams)

      logger.debug(s"Executing solr query: ${solrParams.toString}")

      val results = queryResponse.getResults

      val total  = results.getNumFound
      val offset = results.getStart

      val tagsWithFrequency: Map[String, Long] = Option(queryResponse.getFacetFields).flatMap(fields => Try(fields.get(0)).toOption).map {
        results =>
          results.getValues.iterator().asScala.foldLeft(Map.empty[String, Long]) {
            case (acc, value) if value.getCount > 0 => acc + (value.getName -> value.getCount)
            case (acc, _)                           => acc
          }
      }.getOrElse(Map.empty[String, Long])

      logger.debug(s"Solr response size: ${results.size()}, tags: ${tagsWithFrequency.mkString(", ")}")

      SearchResult(offset = offset.toInt, total = total.toInt, results = results.asScala.map(toResource).toList, tags = tagsWithFrequency)
    }

  override def searchAll(query: Query): fs2.Stream[IO, ResourceInfo] = {
    val pageSize = math.max(1, query.n)

    def page(offset: Int): fs2.Stream[IO, ResourceInfo] =
      fs2.Stream.eval(searchMedia(query.copy(offset = Some(offset)))).flatMap { result =>
        val results = fs2.Stream.emits(result.results)
        if result.results.isEmpty || offset + result.results.size >= result.total then results
        else results ++ page(offset + pageSize)
      }

    page(query.offset.getOrElse(0))
  }
}
