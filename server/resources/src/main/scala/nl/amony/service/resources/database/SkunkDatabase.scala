package nl.amony.service.resources.database

import cats.effect.{IO, Resource}
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
import scribe.Logging

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
      resourceId = hash,
      userId = "0",
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

case class SkunkDatabaseConfig(
  host: String,
  port: Int,
  user: String,
  database: String,
  password: Option[String]
)

object SkunkDatabase:
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

  object queries:
    val bucketCount: Query[String, Int] =
      sql"select count(*) from resources where bucket_id = $varchar".query(int4)

    val insert =
      sql"insert into resources SELECT * FROM json_populate_record(NULL::resources, $json)".command

    val selectById =
      sql"select to_json(r.*) from resources r where bucket_id = $varchar and resource_id = $varchar".query(json).map(_.as[SkunkResourceRow])

  def bucketCount(bucketId: String): IO[Int] =
    session.unique(queries.bucketCount)(bucketId)

  def insertRow(row: SkunkResourceRow) =
    session.prepare(queries.insert).flatMap(_.execute(row.asJson))

  def getRowById(bucketId: String, resourceId: String) =
    session.prepare(queries.selectById).flatMap(_.option(bucketId, resourceId))
