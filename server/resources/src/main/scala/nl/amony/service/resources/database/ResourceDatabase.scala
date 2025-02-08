package nl.amony.service.resources.database

import cats.data.OptionT
import cats.effect.{IO, Resource}
import io.circe.Json
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import skunk.circe.codec.all.*
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import io.circe.syntax.*
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import nl.amony.service.resources.api.events.ResourceEvent
import scribe.Logging
import skunk.data.{Arr, Completion}

import java.sql.{Connection, DriverManager}

case class DatabaseConfig(
  host: String,
  port: Int,
  user: String,
  database: String,
  password: Option[String]
)

object ResourceDatabase:

  def make(config: DatabaseConfig): Resource[IO, ResourceDatabase] = {
    def init() = {

      var connection: Connection = null

      try {
        Class.forName("org.postgresql.Driver")
        val jdbcUrl = s"jdbc:postgresql://${config.host}:${config.port}/${config.database}"
        connection = DriverManager.getConnection(jdbcUrl, config.user, config.password.getOrElse(null))
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
      user = config.user,
      database = config.database,
      password = config.password,
    ).map { session =>
      init()
      new ResourceDatabase(session)
    }
}

class ResourceDatabase(session: Session[IO]) extends Logging:

  val defaultChunkSize = 128

  private[database] object tables {

    object resources {
      object queries {
        val delete: Command[(String, String)] =
          sql"delete from resources where bucket_id = $varchar and resource_id = $varchar".command

        def all(bucketId: String): Query[String, ResourceRow] =
          sql"select to_json(r.*) from resources r where bucket_id = $varchar".query(json).map(_.as[ResourceRow].toOption.get)

        def allJoined(bucketId: String): Query[String, (ResourceRow, Arr[String])] =
          sql"""
           SELECT to_json(r.*), array_agg(t.label) FROM resources r
           LEFT JOIN resource_tags rt
             ON r.bucket_id = rt.bucket_id AND r.resource_id = rt.resource_id
           LEFT JOIN tags t ON rt.tag_id = t.id
           WHERE r.bucket_id = $varchar
           GROUP BY (${ResourceRow.columns})
          """.query(json *: _varchar).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

        val getById: Query[(String, String), ResourceRow] =
          sql"select to_json(r.*) from resources r where bucket_id = $varchar and resource_id = $varchar"
            .query(json)
            .map(_.as[ResourceRow].left.map(err => logger.warn(err)).toOption.get)

        val insert: Command[Json] =
          sql"insert into resources SELECT * FROM json_populate_record(NULL::resources, $json)".command

        val upsert: Command[Json] =
          sql"""
            INSERT INTO resources SELECT * FROM json_populate_record(NULL::resources, $json)
            ON CONFLICT (bucket_id, resource_id) DO UPDATE
            SET(user_id, hash, size, content_type, content_meta_tool_name, content_meta_tool_data, fs_path, fs_creation_time, fs_last_modified_time, title, description, thumbnail_timestamp) =
            (EXCLUDED.user_id, EXCLUDED.hash, EXCLUDED.size, EXCLUDED.content_type, EXCLUDED.content_meta_tool_name, EXCLUDED.content_meta_tool_data, EXCLUDED.fs_path, EXCLUDED.fs_creation_time, EXCLUDED.fs_last_modified_time, EXCLUDED.title, EXCLUDED.description, EXCLUDED.thumbnail_timestamp)
          """.command

        val bucketCount: Query[String, Int] =
          sql"select count(*) from resources where bucket_id = $varchar".query(int4)
      }

      def all(bucketId: String): IO[List[ResourceRow]] =
        session.prepare(queries.all(bucketId))
          .flatMap(_.stream(bucketId, defaultChunkSize).compile.toList)

      def insert(row: ResourceRow): IO[Completion] =
        session.prepare(queries.insert).flatMap(_.execute(row.asJson))

      def upsert(row: ResourceRow): IO[Completion] =
        session.prepare(queries.upsert).flatMap(_.execute(row.asJson))

      def getById(bucketId: String, resourceId: String): IO[Option[ResourceRow]] =
        session.prepare(queries.getById).flatMap(_.option(bucketId, resourceId))

      def delete(bucketId: String, resourceId: String) =
        session.prepare(queries.delete).flatMap(_.execute(bucketId, resourceId))
    }

    object resource_tags {

      object queries {

        def upsert(n: Int): Command[List[ResourceTagsRow]] =
          sql"insert into resource_tags (bucket_id, resource_id, tag_id) values ${ResourceTagsRow.codec.values.list(n)} on conflict (bucket_id, resource_id, tag_id) do nothing".command

        val getById: Query[(String, String), ResourceTagsRow] =
          sql"select bucket_id, resource_id, tag_id from resource_tags where bucket_id = ${varchar(64)} and resource_id = ${varchar(64)}".query(ResourceTagsRow.codec)

        def delete: Command[(String, String)] =
          sql"delete from resource_tags where bucket_id = $varchar and resource_id = $varchar".command
      }

      def getById(bucketId: String, resourceId: String): IO[List[ResourceTagsRow]] =
        session.prepare(queries.getById).flatMap(_.stream((bucketId, resourceId), defaultChunkSize).compile.toList)

      def replaceAll(bucketId: String, resourceId: String, tagIds: List[Int]): IO[Completion] =
        for {
          _          <- session.prepare(queries.delete).flatMap(_.execute(bucketId, resourceId))
          rows       = tagIds.map(tagId => ResourceTagsRow(bucketId, resourceId, tagId))
          completion <- session.prepare(queries.upsert(rows.size)).flatMap(_.execute(rows))
        } yield completion

      def upsert(tags: List[ResourceTagsRow]) =
        session.prepare(queries.upsert(tags.size)).flatMap(_.execute(tags))
    }

    object tags {

      object queries {

        val all: Query[Void, TagRow] = sql"select id, label from tags".query(tagRow)

        def truncate: Command[Void] = sql"truncate table tags".command

        def getByLabels(n: Int): Query[List[String], TagRow] =
          sql"select id,label from tags where label in (${varchar.list(n)})".query(tagRow)

        def getByIds(n: Int): Query[List[Int], TagRow] =
          sql"select id,label from tags where id in (${int4.list(n)})".query(tagRow)

        def insert(n: Int): Command[List[String]] =
          sql"insert into tags (label) values ${varchar.values.list(n)}".command

        def upsertSql(n: Int): Command[List[String]] =
          sql"insert into tags (label) values ${varchar.values.list(n)} on conflict (label) do nothing".command

        def upsert(n: Int): Command[List[String]] =
          sql"""
            WITH new_tags (label) AS (VALUES ${varchar.values.list(n)})
            INSERT INTO tags (label)
            SELECT label
            FROM new_tags
            WHERE NOT EXISTS (SELECT 1 FROM tags WHERE tags.label = new_tags.label)
          """.command
      }

      def all =
        session.prepare(queries.all).flatMap(_.stream(Void, defaultChunkSize).compile.toList)

      def upsert(tagLabels: Set[String]): IO[Completion] =
        session.prepare(queries.upsert(tagLabels.size)).flatMap(_.execute(tagLabels.toList))

      def getByLabels(labels: List[String]) =
        session.prepare(queries.getByLabels(labels.size)).flatMap(_.stream(labels, defaultChunkSize).compile.toList)

      def getByIds(ids: List[Int]) =
        session.prepare(queries.getByIds(ids.size)).flatMap(_.stream(ids, defaultChunkSize).compile.toList)
    }
  }

  def insertResource(resource: ResourceInfo): IO[Completion] = {
    session.transaction.use { tx =>
      for {
        _           <- tables.resources.insert(ResourceRow.fromResource(resource))
        _           <- tables.tags.upsert(resource.tags)
        tags        <- tables.tags.getByLabels(resource.tags.toList)
        resourceTags = tags.map(tag => ResourceTagsRow(resource.bucketId, resource.resourceId, tag.id))
        completion  <- tables.resource_tags.upsert(resourceTags)
      } yield completion
    }
  }

  def upsert(resource: ResourceInfo): IO[Completion] = {
    session.transaction.use { tx =>
      for {
        _ <- tables.resources.upsert(ResourceRow.fromResource(resource))
        _ <- tables.tags.upsert(resource.tags)
        tags <- tables.tags.getByLabels(resource.tags.toList)
        tagIds = tags.map(_.id)
        completion <- tables.resource_tags.replaceAll(resource.bucketId, resource.resourceId, tagIds)
      } yield completion
    }
  }

  def getAll(bucketId: String): IO[List[ResourceInfo]] =
    getStream(bucketId).compile.toList

  def getStream(bucketId: String) =
    fs2.Stream.force(
      session.prepare(tables.resources.queries.allJoined(bucketId)).map(
        _.stream(bucketId, defaultChunkSize).map((resourceRow, tagLabels) => resourceRow.toResource(tagLabels.flattenTo(Set)))
      )
    )

  def getById(bucketId: String, resourceId: String): IO[Option[ResourceInfo]] =
    val result =
      for {
        resourceRow  <- OptionT(tables.resources.getById(bucketId, resourceId))
        resourceTags <- OptionT.liftF(tables.resource_tags.getById(bucketId, resourceId))
        tags         <- OptionT.liftF(tables.tags.getByIds(resourceTags.map(_.tag_id)))
      } yield resourceRow.toResource(tags.map(_.label).toSet)

    result.value

  def updateThumbnailTimestamp(bucketId: String, resourceId: String, timestamp: Int): IO[Option[ResourceInfo]] =
    (for {
      resource <- OptionT(getById(bucketId, resourceId))
      updated  = resource.copy(thumbnailTimestamp = Some(timestamp))
      _       <- OptionT.liftF(tables.resources.insert(ResourceRow.fromResource(updated)))
    } yield updated).value

  def updateUserMeta(bucketId: String, resourceId: String, title: Option[String], description: Option[String], tagLabels: List[String]): IO[Option[ResourceInfo]] =
    (for {
      resource <- OptionT(getById(bucketId, resourceId))
      updated   = resource.copy(title = title, description = description)
      _        <- OptionT.liftF(tables.resources.insert(ResourceRow.fromResource(updated)))
    } yield updated).value

  def move(bucketId: String, resourceId: String, newPath: String): IO[Unit] =
    tables.resources.getById(bucketId, resourceId).flatMap {
      case Some(old) => tables.resources.insert(old.copy(fs_path = newPath)) >> IO.unit
      case None      => IO.unit
    }

  def deleteResource(bucketId: String, resourceId: String): IO[Completion] =
    tables.resources.delete(bucketId, resourceId)

  def applyEvent(bucketId: String, effect: ResourceEvent => IO[Unit])(event: ResourceEvent): IO[Unit] = {
    event match {
      case ResourceAdded(resource)             => insertResource(resource) >> effect(event)
      case ResourceDeleted(resourceId)         => deleteResource(bucketId, resourceId) >> effect(event)
      case ResourceMoved(id, oldPath, newPath) => move(bucketId, id, newPath) >> effect(event)
      case _ => IO.unit
    }
  }

