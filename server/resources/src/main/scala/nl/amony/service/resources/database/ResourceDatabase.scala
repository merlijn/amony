package nl.amony.service.resources.database

import cats.data.OptionT
import cats.effect.{IO, Resource}
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import skunk.*
import skunk.circe.codec.all.*
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import io.circe.syntax.*
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import nl.amony.service.resources.api.events.ResourceEvent
import skunk.data.{Arr, Completion}

import java.sql.{Connection, DriverManager}

case class DatabaseConfig(
   host: String,
   port: Int,
   database: String,
   username: String,
   password: Option[String]
)

object ResourceDatabase:

  def make(config: DatabaseConfig): Resource[IO, ResourceDatabase] = {
    def runDbMigrations() = {

      var connection: Connection = null

      try {
        Class.forName("org.postgresql.Driver")
        val jdbcUrl = s"jdbc:postgresql://${config.host}:${config.port}/${config.database}"
        connection = DriverManager.getConnection(jdbcUrl, config.username, config.password.getOrElse(null))
        val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        val liquibase = new Liquibase("db/00-changelog.yaml", new ClassLoaderResourceAccessor(), liquibaseDatabase)
        liquibase.update()
      }
      finally {
        if (connection != null)
          connection.close()
      }
    }

    Session.single[IO](
      host = config.host,
      port = config.port,
      user = config.username,
      database = config.database,
      password = config.password,
    ).map { session =>
      runDbMigrations()
      new ResourceDatabase(session)
    }
}

class ResourceDatabase(session: Session[IO]):

  val defaultChunkSize = 128

  // table specific methods
  private[database] object tables {

    object resources {

      def insert(row: ResourceRow): IO[Completion] =
        session.prepare(Queries.resources.insert).flatMap(_.execute(row.asJson))
          .recoverWith {
            case SqlState.UniqueViolation(_) => IO.raiseError(new Exception(s"Resource with path ${row.fs_path} already exists"))
          }

      def upsert(row: ResourceRow): IO[Completion] =
        session.prepare(Queries.resources.upsert).flatMap(_.execute(row.asJson))
          .recoverWith {
            case SqlState.UniqueViolation(_) => IO.raiseError(new Exception(s"Resource with path ${row.fs_path} already exists"))
          }

      def getById(bucketId: String, resourceId: String): IO[Option[ResourceRow]] =
        session.prepare(Queries.resources.getById).flatMap(_.option(bucketId, resourceId))

      def delete(bucketId: String, resourceId: String) =
        session.prepare(Queries.resources.deleteBucket).flatMap(_.execute(bucketId, resourceId))
    }

    object resource_tags {

      def getById(bucketId: String, resourceId: String): IO[List[ResourceTagsRow]] =
        session.prepare(Queries.resource_tags.getById).flatMap(_.stream((bucketId, resourceId), defaultChunkSize).compile.toList)

      def replaceAll(bucketId: String, resourceId: String, tagIds: List[Int]): IO[Unit] =
        for {
          _    <- session.prepare(Queries.resource_tags.delete).flatMap(_.execute(bucketId, resourceId))
          rows = tagIds.map(tagId => ResourceTagsRow(bucketId, resourceId, tagId))
          _    <- if (tagIds.nonEmpty) session.prepare(Queries.resource_tags.upsert(rows.size)).flatMap(_.execute(rows)) else IO.unit
        } yield ()

      def delete(bucketId: String, resourceId: String): IO[Completion] =
        session.prepare(Queries.resource_tags.delete).flatMap(_.execute(bucketId, resourceId))

      def upsert(bucketId: String, resourceId: String, tagIds: List[Int]) =
        val rows = tagIds.map(tagId => ResourceTagsRow(bucketId, resourceId, tagId))
        session.prepare(Queries.resource_tags.upsert(rows.size)).flatMap(_.execute(rows))
    }

    object tags {

      def all =
        session.prepare(Queries.tags.all).flatMap(_.stream(Void, defaultChunkSize).compile.toList)

      def upsert(tagLabels: List[String]): IO[Completion] =
        session.prepare(Queries.tags.upsert(tagLabels.size)).flatMap(_.execute(tagLabels.toList))

      def getByLabels(labels: List[String]): IO[List[TagRow]] =
        session.prepare(Queries.tags.getByLabels(labels.size)).flatMap(_.stream(labels, defaultChunkSize).compile.toList)

      def getByIds(ids: List[Int]): IO[List[TagRow]] =
        session.prepare(Queries.tags.getByIds(ids.size)).flatMap(_.stream(ids, defaultChunkSize).compile.toList)
    }
  }

  def insertResource(resource: ResourceInfo): IO[Unit] =
    session.transaction.use: tx =>
      for {
        _ <- tables.resources.insert(ResourceRow.fromResource(resource))
        _ <- updateResourceTags(resource.bucketId, resource.resourceId, resource.tags.toList)
      } yield ()

  def upsert(resource: ResourceInfo): IO[Unit] =
    session.transaction.use: tx =>
      for {
        _  <- tables.resources.upsert(ResourceRow.fromResource(resource))
        _  <- updateResourceTags(resource.bucketId, resource.resourceId, resource.tags.toList)
      } yield ()

  private def updateResourceTags(bucketId: String, resourceId: String, tagLabels: List[String]) =
    for {
      tags <- if (tagLabels.nonEmpty) tables.tags.upsert(tagLabels) >> tables.tags.getByLabels(tagLabels) else IO.pure(List.empty)
      _    <- tables.resource_tags.replaceAll(bucketId, resourceId, tags.map(_.id))
    } yield ()

  def getAll(bucketId: String): IO[List[ResourceInfo]] =
    getStream(bucketId).compile.toList

  def getStream(bucketId: String): fs2.Stream[IO, ResourceInfo] =
    fs2.Stream.force(
      session.prepare(Queries.resources.allJoined).map(
        _.stream(bucketId, defaultChunkSize)
          .map((resourceRow, tagLabels) => resourceRow.toResource(tagLabels.map(_.flattenTo(Set)).getOrElse(Set.empty)))
      )
    )

  def getById(bucketId: String, resourceId: String): IO[Option[ResourceInfo]] =
    (for 
       resourceRow  <- OptionT(tables.resources.getById(bucketId, resourceId))
       resourceTags <- OptionT.liftF(tables.resource_tags.getById(bucketId, resourceId))
       tags         <- if (resourceTags.nonEmpty) OptionT.liftF(tables.tags.getByIds(resourceTags.map(_.tag_id))) else OptionT.some[IO](List.empty)
     yield resourceRow.toResource(tags.map(_.label).toSet)).value

  def updateThumbnailTimestamp(bucketId: String, resourceId: String, timestamp: Int): IO[Option[ResourceInfo]] =
    (for {
      resource <- OptionT(getById(bucketId, resourceId))
      updated  = resource.copy(thumbnailTimestamp = Some(timestamp))
      _       <- OptionT.liftF(tables.resources.upsert(ResourceRow.fromResource(updated)))
    } yield updated).value

  def updateUserMeta(bucketId: String, resourceId: String, title: Option[String], description: Option[String], tagLabels: List[String]): IO[Option[ResourceInfo]] =
    session.transaction.use: tx =>
      getById(bucketId, resourceId).flatMap:
        case None           => IO.pure(None)
        case Some(resource) =>
          val updated = resource.copy(title = title, description = description, tags = tagLabels.toSet)
          for {
            _ <- tables.resources.upsert(ResourceRow.fromResource(updated))
            _ <- updateResourceTags(bucketId, resourceId, tagLabels)
          } yield Some(updated)

  def move(bucketId: String, resourceId: String, newPath: String): IO[Unit] =
    tables.resources.getById(bucketId, resourceId).flatMap:
      case Some(old) => tables.resources.upsert(old.copy(fs_path = newPath)) >> IO.unit
      case None      => IO.unit

  def deleteResource(bucketId: String, resourceId: String): IO[Unit] =
    session.transaction.use: tx =>
      for {
        _ <- tables.resource_tags.delete(bucketId, resourceId)
        _ <- tables.resources.delete(bucketId, resourceId)
      } yield ()

  private[resources] def truncateTables(): IO[Unit] =
    session.transaction.use: tx =>
      for {
        _ <- session.execute(Queries.tags.truncateCascade)
        _ <- session.execute(Queries.resource_tags.truncateCascade)
        _ <- session.execute(Queries.resources.truncateCascade)
      } yield ()

  def applyEvent(bucketId: String, effect: ResourceEvent => IO[Unit])(event: ResourceEvent): IO[Unit] = {
    event match {
      case ResourceAdded(resource)       => insertResource(resource) >> effect(event)
      case ResourceDeleted(resourceId)   => deleteResource(bucketId, resourceId) >> effect(event)
      case ResourceMoved(id, _, newPath) => move(bucketId, id, newPath) >> effect(event)
      case ResourceFileMetaChanged(id, creationTime, lastModifiedTime) =>
        getById(bucketId, id).flatMap {
          case Some(resource) =>
            val updated = resource.copy(creationTime = creationTime, lastModifiedTime = lastModifiedTime)
            tables.resources.upsert(ResourceRow.fromResource(updated)) >> effect(event)
          case None => IO.unit
        }
      case _ => IO.unit
    }
  }

