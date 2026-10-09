package nl.amony.modules.resources.dal

import java.nio.file.Path
import scala.concurrent.duration.DurationLong

import cats.effect.{IO, Resource}
import io.circe.syntax.*
import io.circe.{Codec, Json}
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.implicits.*

import nl.amony.modules.auth.api.Role
import nl.amony.modules.resources.ResourceConfig.{HashingAlgorithm, LocalDirectoryConfig, ResourceBucketConfig, ScanConfig}
import nl.amony.modules.resources.api.BucketId

case class BucketRow(bucket_id: String, bucket_type: String, required_role: Option[String], settings: Json)

case class ScanSettings(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
  scanParallelFactor: Int,
  pollIntervalSeconds: Long,
  includePatterns: List[String],
  excludePatterns: List[String]
) derives Codec

case class LocalDirectorySettings(
  path: String,
  relativeUploadPath: String,
  generatePreviewsOnAdd: Boolean,
  hashingAlgorithm: String,
  sync: ScanSettings
) derives Codec

object BucketRow:

  val localDirectoryType = "LocalDirectory"

  val codec: skunk.Codec[BucketRow] = (varchar(64) *: varchar(32) *: varchar(64).opt *: jsonb).to[BucketRow]

  def fromConfig(config: ResourceBucketConfig): BucketRow = config match
    case c: LocalDirectoryConfig =>
      val settings = LocalDirectorySettings(
        path                  = c.path.toString,
        relativeUploadPath    = c.relativeUploadPath.toString,
        generatePreviewsOnAdd = c.generatePreviewsOnAdd,
        hashingAlgorithm      = c.hashingAlgorithm.name,
        sync                  = ScanSettings(
          enabled             = c.sync.enabled,
          syncOnStartup       = c.sync.syncOnStartup,
          newFilesOwner       = c.sync.newFilesOwner,
          scanParallelFactor  = c.sync.scanParallelFactor,
          pollIntervalSeconds = c.sync.pollInterval.toSeconds,
          includePatterns     = c.sync.includePatterns,
          excludePatterns     = c.sync.excludePatterns
        )
      )
      BucketRow(c.id, localDirectoryType, c.requiredRole.map(_.toString), settings.asJson)

  def toConfig(row: BucketRow): Either[String, ResourceBucketConfig] = row.bucket_type match
    case `localDirectoryType` =>
      for
        settings  <- row.settings.as[LocalDirectorySettings].left.map(_.getMessage)
        algorithm <- HashingAlgorithm.fromName(settings.hashingAlgorithm).toRight(s"Unknown hashing algorithm: ${settings.hashingAlgorithm}")
      yield LocalDirectoryConfig(
        id                    = row.bucket_id,
        requiredRole          = row.required_role.map(Role(_)),
        path                  = Path.of(settings.path),
        sync                  = ScanConfig(
          enabled            = settings.sync.enabled,
          syncOnStartup      = settings.sync.syncOnStartup,
          newFilesOwner      = settings.sync.newFilesOwner,
          scanParallelFactor = settings.sync.scanParallelFactor,
          pollInterval       = settings.sync.pollIntervalSeconds.seconds,
          includePatterns    = settings.sync.includePatterns,
          excludePatterns    = settings.sync.excludePatterns
        ),
        hashingAlgorithm      = algorithm,
        relativeUploadPath    = Path.of(settings.relativeUploadPath),
        generatePreviewsOnAdd = settings.generatePreviewsOnAdd
      )
    case other                => Left(s"Unknown bucket type: $other")

class BucketsDal(pool: Resource[IO, Session[IO]]) extends scribe.Logging:

  private object queries:
    val all: Query[Void, BucketRow] =
      sql"select bucket_id, bucket_type, required_role, settings from buckets order by bucket_id".query(BucketRow.codec)

    val getById: Query[String, BucketRow] =
      sql"select bucket_id, bucket_type, required_role, settings from buckets where bucket_id = ${varchar(64)}".query(BucketRow.codec)

    val insert: Command[BucketRow] =
      sql"insert into buckets (bucket_id, bucket_type, required_role, settings) values (${BucketRow.codec})".command

    val insertIfNoneExist: Command[BucketRow] =
      sql"""
        insert into buckets (bucket_id, bucket_type, required_role, settings)
        select * from (values (${BucketRow.codec})) as v
        where not exists (select 1 from buckets)
        on conflict do nothing
      """.command

    val update: Command[(Option[String], Json, String)] =
      sql"update buckets set required_role = ${varchar(64).opt}, settings = $jsonb, updated_at = now() where bucket_id = ${varchar(64)}".command

    val delete: Command[String]             = sql"delete from buckets where bucket_id = ${varchar(64)}".command
    val deleteResourceTags: Command[String] = sql"delete from resource_tags where bucket_id = ${varchar(64)}".command
    val deleteResources: Command[String]    = sql"delete from resources where bucket_id = ${varchar(64)}".command

  private def toConfig(row: BucketRow): Option[ResourceBucketConfig] =
    BucketRow.toConfig(row) match
      case Right(config) => Some(config)
      case Left(error)   =>
        logger.error(s"Ignoring invalid bucket configuration for '${row.bucket_id}': $error")
        None

  def getAll(): IO[List[ResourceBucketConfig]] =
    pool.use(_.execute(queries.all)).map(_.flatMap(toConfig))

  def getById(bucketId: BucketId): IO[Option[ResourceBucketConfig]] =
    pool.use(_.prepare(queries.getById).flatMap(_.option(bucketId))).map(_.flatMap(toConfig))

  /** Returns false when a bucket with the same id already exists. */
  def insert(config: ResourceBucketConfig): IO[Boolean] =
    pool.use(_.prepare(queries.insert).flatMap(_.execute(BucketRow.fromConfig(config)))).as(true)
      .recover { case SqlState.UniqueViolation(_) => false }

  /** Inserts the given bucket only when no buckets exist yet. Returns whether it was inserted. */
  def insertIfNoneExist(config: ResourceBucketConfig): IO[Boolean] =
    pool.use(_.prepare(queries.insertIfNoneExist).flatMap(_.execute(BucketRow.fromConfig(config)))).map {
      case skunk.data.Completion.Insert(count) => count > 0
      case _                                   => false
    }

  /** Returns false when no bucket with this id exists. */
  def update(config: ResourceBucketConfig): IO[Boolean] =
    val row = BucketRow.fromConfig(config)
    pool.use(_.prepare(queries.update).flatMap(_.execute((row.required_role, row.settings, row.bucket_id)))).map {
      case skunk.data.Completion.Update(count) => count > 0
      case _                                   => false
    }

  /** Deletes the bucket together with all its resources (and their tags and collection memberships) from the database. */
  def deleteWithResources(bucketId: BucketId): IO[Unit] =
    pool.use: s =>
      s.transaction.use: _ =>
        for
          _ <- s.prepare(queries.deleteResourceTags).flatMap(_.execute(bucketId))
          _ <- s.prepare(queries.deleteResources).flatMap(_.execute(bucketId))
          _ <- s.prepare(queries.delete).flatMap(_.execute(bucketId))
        yield ()
