package nl.amony.modules.search

import cats.effect.IO
import fs2.Stream

import nl.amony.modules.resources.api.{BucketId, ResourceBucket, ResourceId, ResourceIndex}
import nl.amony.modules.search.api.{Query, SearchIndexer, SearchService}

class SearchResourceIndex(searchService: SearchService, indexer: SearchIndexer) extends ResourceIndex:

  override def reindex(bucket: ResourceBucket): IO[Unit] =
    indexer.deleteBucket(bucket.id) >>
      indexer.indexAll(bucket.getAllResources) >>
      indexer.forceCommit()

  override def nonStreamable(bucketId: BucketId): Stream[IO, ResourceId] =
    searchService.searchAll(Query(n = 100, streamable = Some(false), includeBuckets = Set(bucketId))).map(_.resourceId)
