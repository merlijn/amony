package nl.amony.modules.resources.dal

import cats.effect.{IO, Resource}
import io.circe.syntax.*
import scribe.Logging
import skunk.*
import skunk.data.{Arr, Completion}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{Collection, CollectionId, ResourceId, ResourceInfo}

trait CollectionsDal(pool: Resource[IO, Session[IO]]) extends Logging:

  protected val defaultChunkSize: Int = 128

  protected def useSession[A](s: Session[IO] => IO[A]): IO[A]                        = pool.use(s)
  protected def useTransaction[A](f: (Session[IO], Transaction[IO]) => IO[A]): IO[A] = pool.use(s => s.transaction.use(tx => f(s, tx)))

  // Abstract helpers that ResourceDatabase must provide
  protected def tagsUpsert(s: Session[IO], tagLabels: List[String]): IO[Completion]
  protected def tagsGetByLabels(s: Session[IO], labels: List[String]): IO[List[TagRow]]
  protected def toResource(resourceRow: ResourceRow, tagLabels: Option[Arr[String]]): ResourceInfo

  private[dal] object collectionTables:
    object collections {
      def insert(s: Session[IO], row: CollectionRow): IO[Completion] =
        s.prepare(Queries.collections.insert).flatMap(_.execute(row.asJson))

      def upsert(s: Session[IO], row: CollectionRow): IO[Completion] =
        s.prepare(Queries.collections.upsert).flatMap(_.execute(row.asJson))

      def delete(s: Session[IO], collectionId: CollectionId): IO[Completion] =
        s.prepare(Queries.collections.delete).flatMap(_.execute(collectionId.value))
    }

    object collection_tags {
      def replaceAll(s: Session[IO], collectionId: CollectionId, tagIds: List[Int]): IO[Unit] =
        for
          _   <- s.prepare(Queries.collection_tags.delete).flatMap(_.execute(collectionId.value))
          rows = tagIds.map(tagId => CollectionTagsRow(collectionId.value, tagId))
          _   <- if tagIds.nonEmpty then s.prepare(Queries.collection_tags.upsert(rows.size)).flatMap(_.execute(rows)) else IO.unit
        yield ()
    }

    object collection_resources {
      def insert(s: Session[IO], collectionId: CollectionId, bucketId: String, resourceId: String): IO[Completion] =
        s.prepare(Queries.collection_resources.insert).flatMap(_.execute(CollectionResourcesRow(collectionId.value, bucketId, resourceId)))

      def delete(s: Session[IO], collectionId: CollectionId, bucketId: String, resourceId: String): IO[Completion] =
        s.prepare(Queries.collection_resources.delete).flatMap(_.execute((collectionId.value, bucketId, resourceId)))
    }

  private def toCollection(collectionRow: CollectionRow, tagLabels: Option[Arr[String]]): Collection =
    collectionRow.toCollection(tagLabels.map(_.flattenTo(Set)).getOrElse(Set.empty))

  private def updateTagsForCollection(s: Session[IO], collection: Collection): IO[Unit] =
    for
      tags <-
        if collection.tags.nonEmpty then tagsUpsert(s, collection.tags.toList) >> tagsGetByLabels(s, collection.tags.toList) else IO.pure(List.empty)
      _    <- collectionTables.collection_tags.replaceAll(s, collection.id, tags.map(_.id))
    yield ()

  private def updateCollectionWithTags(s: Session[IO], collection: Collection): IO[Unit] =
    for
      _ <- collectionTables.collections.upsert(s, CollectionRow.fromCollection(collection))
      _ <- updateTagsForCollection(s, collection)
    yield ()

  private def getCollectionByIdWithSession(s: Session[IO], collectionId: CollectionId): IO[Option[Collection]] =
    s.prepare(Queries.collections.getByIdJoined)
      .flatMap(_.stream(collectionId.value, defaultChunkSize).map(toCollection).compile.toList.map(_.headOption))

  def getAllCollections(): IO[List[Collection]] =
    useSession: s =>
      s.prepare(Queries.collections.allJoined).flatMap(_.stream(Void, defaultChunkSize).map(toCollection).compile.toList)

  def getCollectionById(collectionId: CollectionId): IO[Option[Collection]] =
    useSession(s => getCollectionByIdWithSession(s, collectionId))

  def getCollectionsByParentId(parentId: Option[CollectionId]): IO[List[Collection]] =
    useSession: s =>
      s.prepare(Queries.collections.getByParentIdJoined).flatMap(_.stream(parentId.map(_.value), defaultChunkSize).map(toCollection).compile.toList)

  def getCollectionsForUser(userId: UserId, maybeParentId: Option[CollectionId] = None): IO[List[Collection]] =
    useSession: s =>
      s.prepare(Queries.collections.getByUserIdJoined).flatMap(_.stream(
        (userId, maybeParentId.map(_.value)),
        defaultChunkSize
      ).map(toCollection).compile.toList)

  def getCollectionsForResource(bucketId: String, resourceId: ResourceId): IO[List[Collection]] =
    useSession: s =>
      s.prepare(Queries.collections.getByResourceIdJoined).flatMap(_.stream(
        (bucketId, resourceId),
        defaultChunkSize
      ).map(toCollection).compile.toList)

  def getResourcesInCollection(collectionId: CollectionId): IO[List[ResourceInfo]] =
    useSession: s =>
      s.prepare(Queries.resources.getByCollectionIdJoined).flatMap(_.stream(collectionId.value, defaultChunkSize).map(toResource).compile.toList)

  def insertCollection(collection: Collection): IO[Unit] =
    useTransaction: (s, _) =>
      for
        _ <- collectionTables.collections.insert(s, CollectionRow.fromCollection(collection))
        _ <- updateTagsForCollection(s, collection)
      yield ()

  def upsertCollection(collection: Collection): IO[Unit] =
    useTransaction: (s, _) =>
      updateCollectionWithTags(s, collection)

  def deleteCollection(collectionId: CollectionId): IO[Unit] =
    useSession: s =>
      collectionTables.collections.delete(s, collectionId).void

  def addResourceToCollection(collectionId: CollectionId, bucketId: String, resourceId: ResourceId): IO[Unit] =
    useSession: s =>
      collectionTables.collection_resources.insert(s, collectionId, bucketId, resourceId).void

  def removeResourceFromCollection(collectionId: CollectionId, bucketId: String, resourceId: ResourceId): IO[Unit] =
    useSession: s =>
      collectionTables.collection_resources.delete(s, collectionId, bucketId, resourceId).void
