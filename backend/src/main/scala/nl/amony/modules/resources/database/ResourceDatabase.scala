package nl.amony.modules.resources.database

import cats.data.OptionT
import cats.effect.{IO, Resource}
import io.circe.syntax.*
import scribe.Logging
import skunk.*
import skunk.data.{Arr, Completion}

import nl.amony.modules.resources.api.ResourceInfo

class ResourceDatabase(pool: Resource[IO, Session[IO]]) extends Logging:

  val defaultChunkSize = 128

  private def useSession[A](s: Session[IO] => IO[A]): IO[A] = pool.use(s)

  private def useTransaction[A](f: (Session[IO], Transaction[IO]) => IO[A]): IO[A] = pool.use(s => s.transaction.use(tx => f(s, tx)))

  // table specific methods
  private[database] object tables {

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

  private[resources] def truncateTables(): IO[Unit] =
    useTransaction: (s, tx) =>
      for
        _ <- s.execute(Queries.tags.truncateCascade)
        _ <- s.execute(Queries.resource_tags.truncateCascade)
        _ <- s.execute(Queries.resources.truncateCascade)
      yield ()

  def getAll(bucketId: String): IO[List[ResourceInfo]] = getStream(bucketId).compile.toList

  def insertResource(resource: ResourceInfo): IO[Unit] =
    useTransaction: (s, tx) =>
      for
        _ <- tables.resources.insert(s, ResourceRow.fromResource(resource))
        _ <- updateTagsForResource(s, resource.bucketId, resource.resourceId, resource.tags.toList)
      yield ()

  def upsert(resource: ResourceInfo): IO[Unit] =
    useTransaction: (s, tx) =>
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

  def getByHash(bucketId: String, hash: String): IO[List[ResourceInfo]] =
    useSession: s =>
      s.prepare(Queries.resources.getByHashJoined).flatMap(_.stream((bucketId, hash), defaultChunkSize).map(toResource).compile.toList)

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
    useTransaction: (s, tx) =>
      getById(bucketId, resourceId).flatMap:
        case None           => IO.pure(None)
        case Some(resource) =>
          val updatedResource = resource.copy(title = title, description = description, tags = tagLabels.toSet)
          updateResourceWithTags(s, updatedResource) >> IO.pure(Some(updatedResource))

  def modifyTags(bucketId: String, resourceId: String, tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Option[ResourceInfo]] =
    useTransaction: (s, tx) =>
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

  def deleteResource(bucketId: String, resourceId: String): IO[Unit] =
    useTransaction: (s, tx) =>
      for
        _ <- tables.resource_tags.delete(s, bucketId, resourceId)
        _ <- tables.resources.delete(s, bucketId, resourceId)
      yield ()
