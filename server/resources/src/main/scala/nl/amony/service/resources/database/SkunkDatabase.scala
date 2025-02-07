package nl.amony.service.resources.database

import cats.data.OptionT
import cats.effect.{IO, Resource}
import io.circe.Json
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaSource}
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
import nl.amony.service.resources.database.SkunkDatabase.{SkunkTagRow, tagRow}
import scribe.Logging
import skunk.data.Completion

import java.sql.{Connection, DriverManager}

case class SkunkResourceRow(
  bucket_id: String,
  resource_id: String,
  user_id: String,
  relative_path: String,
  hash: String,
  size: Long,
  content_type: Option[String],
  content_meta_tool_name: Option[String],
  content_meta_tool_data: Option[String],
  creation_time: Option[Long],
  last_modified_time: Option[Long],
  title: Option[String],
  description: Option[String],
  thumbnail_timestamp: Option[Long] = None) derives io.circe.Codec {

  def toResource(tagLabels: Set[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucket_id,
      resourceId = resource_id,
      userId = user_id,
      path = relative_path,
      hash = Some(hash),
      size = size,
      contentType = content_type,
      contentMetaSource = content_meta_tool_name.map(name => ResourceMetaSource(name, content_meta_tool_data.getOrElse(""))),
      contentMeta = ResourceMeta.Empty,
      tags = tagLabels,
      creationTime = creation_time,
      lastModifiedTime = last_modified_time,
      title = title,
      description = description,
      thumbnailTimestamp = thumbnail_timestamp
    )
  }
}

object SkunkResourceRow {

  def fromResource(resource: ResourceInfo): SkunkResourceRow = SkunkResourceRow(
    bucket_id = resource.bucketId,
    resource_id = resource.resourceId,
    user_id = resource.userId,
    relative_path = resource.path,
    hash = resource.hash.get,
    size = resource.size,
    content_type = resource.contentType,
    content_meta_tool_name = resource.contentMetaSource.map(_.toolName),
    content_meta_tool_data = resource.contentMetaSource.map(_.toolData),
    creation_time = resource.creationTime,
    last_modified_time = resource.lastModifiedTime,
    title = resource.title,
    description = resource.description,
    thumbnail_timestamp = resource.thumbnailTimestamp
  )
}

case class ResourceTagsRow(
  bucket_id: String,
  resource_id: String,
  tag_id: Int
)

object ResourceTagsRow:
  val codec: Codec[ResourceTagsRow] = (varchar(128) *: varchar(128) *: int4).to[ResourceTagsRow]

case class SkunkDatabaseConfig(
  host: String,
  port: Int,
  user: String,
  database: String,
  password: Option[String]
)

object SkunkDatabase:

  case class SkunkTagRow(
     id: Int,
     label: String
   )

  val tagRow: Codec[SkunkTagRow] =
    (int4 *: varchar(64)).imap((SkunkTagRow.apply _).tupled)(tag => (tag.id, tag.label))

  def make(config: SkunkDatabaseConfig): Resource[IO, SkunkDatabase] = {
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
      new SkunkDatabase(session)
    }
}

class SkunkDatabase(session: Session[IO]) extends Logging:

  private[database] object tables {

    object resources {
      object queries {
        val delete: Command[(String, String)] =
          sql"delete from resources where bucket_id = $varchar and resource_id = $varchar".command

        val getById: Query[(String, String), SkunkResourceRow] =
          sql"select to_json(r.*) from resources r where bucket_id = $varchar and resource_id = $varchar".query(json).map(_.as[SkunkResourceRow].toOption.get)

        val insertResourceRow: Command[Json] =
          sql"insert into resources SELECT * FROM json_populate_record(NULL::resources, $json)".command

        val bucketCount: Query[String, Int] =
          sql"select count(*) from resources where bucket_id = $varchar".query(int4)
      }

      def insert(row: SkunkResourceRow) =
        session.prepare(queries.insertResourceRow).flatMap(_.execute(row.asJson))

      def getById(bucketId: String, resourceId: String): IO[Option[SkunkResourceRow]] =
        session.prepare(queries.getById).flatMap(_.option(bucketId, resourceId))

      def delete(bucketId: String, resourceId: String) =
        session.prepare(queries.delete).flatMap(_.execute(bucketId, resourceId))
    }

    object resource_tags {

      object queries {
        def upsert(n: Int): Command[List[ResourceTagsRow]] =
          sql"insert into resource_tags (bucket_id, resource_id, tag_id) values ${ResourceTagsRow.codec.values.list(n)} on conflict (bucket_id, resource_id, tag_id) do nothing".command

        val getById: Query[(String, String), ResourceTagsRow] =
          sql"select bucket_id, resource_id, tag_id from resource_tags where bucket_id = ${varchar(128)} and resource_id = ${varchar(128)}".query(ResourceTagsRow.codec)
      }

      def getById(bucketId: String, resourceId: String): IO[List[ResourceTagsRow]] =
        session.prepare(queries.getById).flatMap(_.stream((bucketId, resourceId), 1024).compile.toList)

      def upsert(tags: List[ResourceTagsRow]) = 
        session.prepare(queries.upsert(tags.size)).flatMap(_.execute(tags))
    }

    object tags {

      object queries {

        val all: Query[Void, SkunkTagRow] = sql"select id, label from tags".query(tagRow)

        def truncate: Command[Void] = sql"truncate table tags".command

        def getByLabels(n: Int): Query[List[String], SkunkTagRow] =
          sql"select id,label from tags where label in (${varchar.list(n)})".query(tagRow)

        def getByIds(n: Int): Query[List[Int], SkunkTagRow] =
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

      val all =
        session.prepare(queries.all).flatMap(_.stream(Void, 1024).compile.toList)

      def upsert(tagLabels: Set[String]): IO[Completion] =
        session.prepare(queries.upsert(tagLabels.size)).flatMap(_.execute(tagLabels.toList))

      def getByLabels(labels: List[String]) =
        session.prepare(queries.getByLabels(labels.size)).flatMap(_.stream(labels, 1024).compile.toList)

      def getByIds(ids: List[Int]) =
        session.prepare(queries.getByIds(ids.size)).flatMap(_.stream(ids, 1024).compile.toList)
    }
  }

  def insertResource(resource: ResourceInfo): IO[Completion] = {
    session.transaction.use { tx =>
      for {
        _           <- tables.resources.insert(SkunkResourceRow.fromResource(resource))
        _           <- tables.tags.upsert(resource.tags)
        tags        <- tables.tags.getByLabels(resource.tags.toList)
        resourceTags = tags.map(tag => ResourceTagsRow(resource.bucketId, resource.resourceId, tag.id))
        completion  <- tables.resource_tags.upsert(resourceTags)
      } yield completion
    }
  }

  def getById(bucketId: String, resourceId: String): IO[Option[ResourceInfo]] =
    val result =
      for {
        resourceRow  <- OptionT(tables.resources.getById(bucketId, resourceId))
        resourceTags <- OptionT.liftF(tables.resource_tags.getById(bucketId, resourceId))
        tags         <- OptionT.liftF(tables.tags.getByIds(resourceTags.map(_.tag_id)))
      } yield resourceRow.toResource(tags.map(_.label).toSet)

    result.value

  def deleteResource(bucketId: String, resourceId: String): IO[Completion] =
    tables.resources.delete(bucketId, resourceId)


