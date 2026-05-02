package nl.amony.modules.resources.dal

import cats.data.OptionT
import cats.effect.{IO, Resource}
import io.circe.syntax.*
import scribe.Logging
import skunk.*
import skunk.data.{Arr, Completion}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{Collection, CollectionId, ResourceId, ResourceInfo}

class ResourceDatabase(pool: Resource[IO, Session[IO]]) extends Logging:

  val defaultChunkSize = 128

  private def useSession[A](s: Session[IO] => IO[A]): IO[A] = pool.use(s)

  private def useTransaction[A](f: (Session[IO], Transaction[IO]) => IO[A]): IO[A] = pool.use(s => s.transaction.use(tx => f(s, tx)))

  // table specific methods
  private[dal] object tables {

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
          _    <- s.prepare(Queries.collection_tags.delete).flatMap(_.execute(collectionId.value))
          rows  = tagIds.map(tagId => CollectionTagsRow(collectionId.value, tagId))
          _    <- if tagIds.nonEmpty then s.prepare(Queries.collection_tags.upsert(rows.size)).flatMap(_.execute(rows)) else IO.unit
        yield ()
    }

    object collection_resources {

      def insert(s: Session[IO], collectionId: CollectionId, bucketId: String, resourceId: String): IO[Completion] =
        s.prepare(Queries.collection_resources.insert).flatMap(_.execute(CollectionResourcesRow(collectionId.value, bucketId, resourceId)))

      def delete(s: Session[IO], collectionId: CollectionId, bucketId: String, resourceId: String): IO[Completion] =
        s.prepare(Queries.collection_resources.delete).flatMap(_.execute((collectionId.value, bucketId, resourceId)))
    }

    object resources {

      def insert(s: Session[IO], row: ResourceRow): IO[Completion] =
        s.prepare(Queries.resources.insert).flatMap(_.execute(row.asJson))
          .recoverWith { case SqlState.UniqueViolation(_) => IO.raiseError(new Exception(s"Resource with path ${row.fs_path} already exists")) }

      def upsert(s: Session[IO], row: ResourceRow): IO[Completion] =
        s.prepare(Queries.resources.upsert).flatMap(_.execute(row.asJson))
          .recoverWith { case SqlState.UniqueViolation(_) => IO.raiseError(new Exception(s"Resource with path ${row.fs_path} already exists")) }

      def getById(s: Session[IO], bucketId: String, resourceId: String): IO[Option[ResourceRow]] =
        s.prepare(Queries.resources.getById).flatMap(_.option(bucketId, resourceId))

      def delete(s: Session[IO], bucketId: String, resourceId: String) =
        s.prepare(Queries.resources.deleteBucket).flatMap(_.execute(bucketId, resourceId))
    }

    object resource_tags {

      def getById(s: Session[IO], bucketId: String, resourceId: String): IO[List[ResourceTagsRow]] =
        s.prepare(Queries.resource_tags.getById).flatMap(_.stream((bucketId, resourceId), defaultChunkSize).compile.toList)

      def replaceAll(s: Session[IO], bucketId: String, resourceId: String, tagIds: List[Int]): IO[Unit] =
        for
          _   <- s.prepare(Queries.resource_tags.delete).flatMap(_.execute(bucketId, resourceId))
          rows = tagIds.map(tagId => ResourceTagsRow(bucketId, resourceId, tagId))
          _   <- if tagIds.nonEmpty then s.prepare(Queries.resource_tags.upsert(rows.size)).flatMap(_.execute(rows)) else IO.unit
        yield ()

      def delete(s: Session[IO], bucketId: String, resourceId: String): IO[Completion] =
        s.prepare(Queries.resource_tags.delete).flatMap(_.execute(bucketId, resourceId))

      def upsert(s: Session[IO], bucketId: String, resourceId: String, tagIds: List[Int]) =
        val rows = tagIds.map(tagId => ResourceTagsRow(bucketId, resourceId, tagId))
        s.prepare(Queries.resource_tags.upsert(rows.size)).flatMap(_.execute(rows))
    }

    object tags {

      def all(s: Session[IO]) = s.prepare(Queries.tags.all).flatMap(_.stream(Void, defaultChunkSize).compile.toList)

      def upsert(s: Session[IO], tagLabels: List[String]): IO[Completion] =
        s.prepare(Queries.tags.upsert(tagLabels.size)).flatMap(_.execute(tagLabels))

      def getByLabels(s: Session[IO], labels: List[String]): IO[List[TagRow]] =
        s.prepare(Queries.tags.getByLabels(labels.size)).flatMap(_.stream(labels, defaultChunkSize).compile.toList)

      def getByIds(s: Session[IO], ids: List[Int]): IO[List[TagRow]] =
        s.prepare(Queries.tags.getByIds(ids.size)).flatMap(_.stream(ids, defaultChunkSize).compile.toList)
    }
  }

  private def toResource(resourceRow: ResourceRow, tagLabels: Option[Arr[String]]): ResourceInfo =
    resourceRow.toResource(tagLabels.map(_.flattenTo(Set)).getOrElse(Set.empty))

  private def toCollection(collectionRow: CollectionRow, tagLabels: Option[Arr[String]]): Collection =
    collectionRow.toCollection(tagLabels.map(_.flattenTo(Set)).getOrElse(Set.empty))

  private def updateTagsForResource(s: Session[IO], bucketId: String, resourceId: String, tagLabels: List[String]) =
    for
      tags <- if tagLabels.nonEmpty then tables.tags.upsert(s, tagLabels) >> tables.tags.getByLabels(s, tagLabels) else IO.pure(List.empty)
      _    <- tables.resource_tags.replaceAll(s, bucketId, resourceId, tags.map(_.id))
    yield ()

  private def updateResourceWithTags(s: Session[IO], resource: ResourceInfo): IO[Unit] =
    for
      _ <- tables.resources.upsert(s, ResourceRow.fromResource(resource))
      _ <- updateTagsForResource(s, resource.bucketId, resource.resourceId, resource.tags.toList)
    yield ()

  private def updateTagsForCollection(s: Session[IO], collection: Collection): IO[Unit] =
    for
      tags <- if collection.tags.nonEmpty then tables.tags.upsert(s, collection.tags.toList) >> tables.tags.getByLabels(s, collection.tags.toList) else IO.pure(List.empty)
      _    <- tables.collection_tags.replaceAll(s, collection.id, tags.map(_.id))
    yield ()

  private def updateCollectionWithTags(s: Session[IO], collection: Collection): IO[Unit] =
    for
      _ <- tables.collections.upsert(s, CollectionRow.fromCollection(collection))
      _ <- updateTagsForCollection(s, collection)
    yield ()

  private def getCollectionByIdWithSession(s: Session[IO], collectionId: CollectionId): IO[Option[Collection]] =
    s.prepare(Queries.collections.getByIdJoined)
      .flatMap(_.stream(collectionId.value, defaultChunkSize).map(toCollection).compile.toList.map(_.headOption))

  private[resources] def truncateTables(): IO[Unit] =
    useTransaction: (s, _) =>
      for
        _ <- s.execute(Queries.collection_resources.truncateCascade)
        _ <- s.execute(Queries.collection_tags.truncateCascade)
        _ <- s.execute(Queries.collections.truncateCascade)
        _ <- s.execute(Queries.tags.truncateCascade)
        _ <- s.execute(Queries.resource_tags.truncateCascade)
        _ <- s.execute(Queries.resources.truncateCascade)
      yield ()

  def getAll(bucketId: String): IO[List[ResourceInfo]] = getStream(bucketId).compile.toList

  def insertResource(resource: ResourceInfo): IO[Unit] =
    useTransaction: (s, _) =>
      for
        _ <- tables.resources.insert(s, ResourceRow.fromResource(resource))
        _ <- updateTagsForResource(s, resource.bucketId, resource.resourceId, resource.tags.toList)
      yield ()

  def upsert(resource: ResourceInfo): IO[Unit] =
    useTransaction: (s, _) =>
      updateResourceWithTags(s, resource)

  def getStream(bucketId: String): fs2.Stream[IO, ResourceInfo] =
    fs2.Stream.force(
      useSession: s =>
        s.prepare(Queries.resources.allJoined).map(_.stream(bucketId, defaultChunkSize).map(toResource))
    )

  def getById(bucketId: String, resourceId: String): IO[Option[ResourceInfo]] =
    useSession: s =>
      s.prepare(Queries.resources.getByIdJoined)
        .flatMap(_.stream((bucketId, resourceId), defaultChunkSize).map(toResource).compile.toList.map(_.headOption))

  def getByPartialHash(bucketId: String, partialHash: String): IO[List[ResourceInfo]] =
    useSession: s =>
      s.prepare(Queries.resources.getByPartialHashJoined).flatMap(_.stream((bucketId, partialHash), defaultChunkSize).map(toResource).compile.toList)

  def updateThumbnailTimestamp(bucketId: String, resourceId: String, timestamp: Int): IO[Option[ResourceInfo]] = useSession: s =>
    (for
      resource <- OptionT(getById(bucketId, resourceId))
      updated   = resource.copy(thumbnailTimestamp = Some(timestamp))
      _        <- OptionT.liftF(tables.resources.upsert(s, ResourceRow.fromResource(updated)))
    yield updated).value

  def updateUserMeta(
    bucketId: String,
    resourceId: String,
    title: Option[String],
    description: Option[String],
    tagLabels: List[String]
  ): IO[Option[ResourceInfo]] =
    useTransaction: (s, _) =>
      getById(bucketId, resourceId).flatMap:
        case None           => IO.pure(None)
        case Some(resource) =>
          val updatedResource = resource.copy(title = title, description = description, tags = tagLabels.toSet)
          updateResourceWithTags(s, updatedResource) >> IO.pure(Some(updatedResource))

  def modifyTags(bucketId: String, resourceId: String, tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Option[ResourceInfo]] =
    useTransaction: (s, _) =>
      getById(bucketId, resourceId).flatMap:
        case None           => IO.pure(None)
        case Some(resource) =>
          val updatedTags     = ((resource.tags ++ tagsToAdd) -- tagsToRemove).toList
          val updatedResource = resource.copy(tags = updatedTags.toSet)
          updateResourceWithTags(s, updatedResource) >> IO.pure(Some(updatedResource))

  def move(bucketId: String, resourceId: String, newPath: String): IO[Unit] =
    useSession: s =>
      tables.resources.getById(s, bucketId, resourceId).flatMap:
        case Some(old) => tables.resources.upsert(s, old.copy(fs_path = newPath)) >> IO.unit
        case None      => IO.unit

  def bucketSize(bucketId: String): IO[Int] =
    useSession: s =>
      s.prepare(Queries.resources.bucketCount).flatMap(_.option(bucketId)).map(_.getOrElse(0))

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
      s.prepare(Queries.collections.getByUserIdJoined).flatMap(_.stream((userId, maybeParentId.map(_.value)), defaultChunkSize).map(toCollection).compile.toList)

  def getCollectionsForResource(bucketId: String, resourceId: ResourceId): IO[List[Collection]] =
    useSession: s =>
      s.prepare(Queries.collections.getByResourceIdJoined).flatMap(_.stream((bucketId, resourceId), defaultChunkSize).map(toCollection).compile.toList)

  def getResourcesInCollection(collectionId: CollectionId): IO[List[ResourceInfo]] =
    useSession: s =>
      s.prepare(Queries.resources.getByCollectionIdJoined).flatMap(_.stream(collectionId.value, defaultChunkSize).map(toResource).compile.toList)

  def insertCollection(collection: Collection): IO[Unit] =
    useTransaction: (s, _) =>
      for
        _ <- tables.collections.insert(s, CollectionRow.fromCollection(collection))
        _ <- updateTagsForCollection(s, collection)
      yield ()

  def upsertCollection(collection: Collection): IO[Unit] =
    useTransaction: (s, _) =>
      updateCollectionWithTags(s, collection)

  def deleteCollection(collectionId: CollectionId): IO[Unit] =
    useSession: s =>
      tables.collections.delete(s, collectionId).void

  def addResourceToCollection(collectionId: CollectionId, bucketId: String, resourceId: ResourceId): IO[Unit] =
    useSession: s =>
      tables.collection_resources.insert(s, collectionId, bucketId, resourceId).void

  def removeResourceFromCollection(collectionId: CollectionId, bucketId: String, resourceId: ResourceId): IO[Unit] =
    useSession: s =>
      tables.collection_resources.delete(s, collectionId, bucketId, resourceId).void

  def deleteResource(bucketId: String, resourceId: String): IO[Unit] =
    useTransaction: (s, _) =>
      for
        _ <- tables.resource_tags.delete(s, bucketId, resourceId)
        _ <- tables.resources.delete(s, bucketId, resourceId)
      yield ()
