package nl.amony.modules.resources.dal

import java.net.URI
import java.nio.file.Path
import java.time.Instant
import scala.concurrent.duration.DurationLong

import cats.data.EitherT
import cats.effect.{IO, Resource}
import io.circe.syntax.*
import io.circe.{Codec, Json}
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.implicits.*

import nl.amony.modules.auth.api.Role
import nl.amony.modules.resources.api.{
  BucketError,
  BucketId,
  LocalDirectoryStorageConfig,
  ResourceBucketConfig,
  S3StorageConfig,
  ScanConfig,
  StoredBucket
}

case class BucketRow(bucket_id: String, bucket_type: String, required_role: Option[String], settings: Json)

case class ScanSettings(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
  pollIntervalSeconds: Long,
  includePatterns: List[String],
  excludePatterns: List[String]
) derives Codec

case class LocalDirectorySettings(
  path: String,
  relativeUploadPath: String,
  generatePreviewsOnAdd: Boolean,
  hashingAlgorithm: Option[String] = None,
  sync: ScanSettings
) derives Codec

case class S3Settings(
  bucket: String,
  region: String,
  endpoint: Option[String]       = None,
  prefix: Option[String]         = None,
  pathStyleAccess: Boolean       = false,
  presignedUrlTtlSeconds: Long   = 900L,
  generatePreviewsOnAdd: Boolean = false
) derives Codec

object BucketRow:

  val localDirectoryType = "LocalDirectory"
  val s3Type             = "S3"

  val codec: skunk.Codec[BucketRow] = (varchar(64) *: varchar(32) *: varchar(64).opt *: jsonb).to[BucketRow]

  def fromConfig(config: ResourceBucketConfig): BucketRow = config.storage match
    case local: LocalDirectoryStorageConfig =>
      val settings = LocalDirectorySettings(
        path                  = local.path.toString,
        relativeUploadPath    = local.relativeUploadPath.toString,
        generatePreviewsOnAdd = config.generatePreviewsOnAdd,
        sync                  = ScanSettings(
          enabled             = local.sync.enabled,
          syncOnStartup       = local.sync.syncOnStartup,
          newFilesOwner       = local.sync.newFilesOwner,
          pollIntervalSeconds = local.sync.pollInterval.toSeconds,
          includePatterns     = local.sync.includePatterns,
          excludePatterns     = local.sync.excludePatterns
        )
      )
      BucketRow(config.id, localDirectoryType, config.requiredRole.map(_.toString), settings.asJson)

    case s3: S3StorageConfig =>
      val settings = S3Settings(
        bucket                 = s3.bucket,
        region                 = s3.region,
        endpoint               = s3.endpoint.map(_.toString),
        prefix                 = s3.prefix,
        pathStyleAccess        = s3.pathStyleAccess,
        presignedUrlTtlSeconds = s3.presignedUrlTtl.toSeconds,
        generatePreviewsOnAdd  = config.generatePreviewsOnAdd
      )
      BucketRow(config.id, s3Type, config.requiredRole.map(_.toString), settings.asJson)

  def toConfig(row: BucketRow): Either[String, ResourceBucketConfig] = row.bucket_type match
    case `localDirectoryType` =>
      row.settings.as[LocalDirectorySettings].left.map(_.getMessage).map { settings =>
        ResourceBucketConfig(
          id                    = row.bucket_id,
          requiredRole          = row.required_role.map(Role(_)),
          generatePreviewsOnAdd = settings.generatePreviewsOnAdd,
          storage               = LocalDirectoryStorageConfig(
            path               = Path.of(settings.path),
            sync               = ScanConfig(
              enabled         = settings.sync.enabled,
              syncOnStartup   = settings.sync.syncOnStartup,
              newFilesOwner   = settings.sync.newFilesOwner,
              pollInterval    = settings.sync.pollIntervalSeconds.seconds,
              includePatterns = settings.sync.includePatterns,
              excludePatterns = settings.sync.excludePatterns
            ),
            relativeUploadPath = Path.of(settings.relativeUploadPath)
          )
        )
      }
    case `s3Type`             =>
      for
        settings <- row.settings.as[S3Settings].left.map(_.getMessage)
        endpoint <- settings.endpoint match
                      case None    => Right(None)
                      case Some(s) =>
                        try Right(Some(URI.create(s)))
                        catch case _: IllegalArgumentException => Left(s"Invalid S3 endpoint URI: $s")
      yield ResourceBucketConfig(
        id                    = row.bucket_id,
        requiredRole          = row.required_role.map(Role(_)),
        generatePreviewsOnAdd = settings.generatePreviewsOnAdd,
        storage               = S3StorageConfig(
          bucket          = settings.bucket,
          region          = settings.region,
          endpoint        = endpoint,
          prefix          = settings.prefix,
          pathStyleAccess = settings.pathStyleAccess,
          presignedUrlTtl = settings.presignedUrlTtlSeconds.seconds
        )
      )
    case other                => Left(s"Unknown bucket type: $other")

class BucketsDal(pool: Resource[IO, Session[IO]]) extends scribe.Logging:

  private object queries:
    private val columns = sql"bucket_id, bucket_type, required_role, settings, updated_at"

    val anyExist: Query[Void, Boolean] = sql"select exists (select 1 from buckets)".query(bool)

    val exists: Query[String, Boolean] = sql"select exists (select 1 from buckets where bucket_id = ${varchar(64)})".query(bool)

    val all: Query[Void, (BucketRow, Instant)] =
      sql"select $columns from buckets order by bucket_id".query(BucketRow.codec *: instantCodec)

    val getById: Query[String, (BucketRow, Instant)] =
      sql"select $columns from buckets where bucket_id = ${varchar(64)}".query(BucketRow.codec *: instantCodec)

    val lockForDelete: Query[String, String] =
      sql"select bucket_id from buckets where bucket_id = ${varchar(64)} for update".query(varchar(64))

    val insert: Query[BucketRow, Instant] =
      sql"""
        insert into buckets (bucket_id, bucket_type, required_role, settings) values (${BucketRow.codec})
        on conflict do nothing
        returning updated_at
      """.query(instantCodec)

    val insertIfNoneExist: Command[BucketRow] =
      sql"""
        insert into buckets (bucket_id, bucket_type, required_role, settings)
        select * from (values (${BucketRow.codec})) as v
        where not exists (select 1 from buckets)
        on conflict do nothing
      """.command

    val update: Query[(Option[String], Json, String, Instant), Instant] =
      sql"""
        update buckets set required_role = ${varchar(64).opt}, settings = $jsonb, updated_at = now()
        where bucket_id = ${varchar(64)} and updated_at = $instantCodec
        returning updated_at
      """.query(instantCodec)

    val resourceCount: Query[String, Long] = sql"select count(*) from resources where bucket_id = ${varchar(64)}".query(int8)

    val delete: Command[String]             = sql"delete from buckets where bucket_id = ${varchar(64)}".command
    val deleteResourceTags: Command[String] = sql"delete from resource_tags where bucket_id = ${varchar(64)}".command
    val deleteResources: Command[String]    = sql"delete from resources where bucket_id = ${varchar(64)}".command

  private def toStored(row: BucketRow, updatedAt: Instant): Option[StoredBucket] =
    BucketRow.toConfig(row) match
      case Right(config) => Some(StoredBucket(config, updatedAt))
      case Left(error)   =>
        logger.error(s"Ignoring invalid bucket configuration for '${row.bucket_id}': $error")
        None

  def anyExist(): IO[Boolean] = pool.use(_.unique(queries.anyExist))

  def getAll(): IO[List[StoredBucket]] =
    pool.use(_.execute(queries.all)).map(_.flatMap(toStored))

  def getById(bucketId: BucketId): IO[Option[StoredBucket]] =
    pool.use(_.prepare(queries.getById).flatMap(_.option(bucketId))).map(_.flatMap(toStored))

  def insert(config: ResourceBucketConfig): IO[Either[BucketError, StoredBucket]] =
    pool.use(_.prepare(queries.insert).flatMap(_.option(BucketRow.fromConfig(config)))).map {
      case Some(updatedAt) => Right(StoredBucket(config, updatedAt))
      case None            => Left(BucketError.AlreadyExists(BucketId(config.id)))
    }

  /** Inserts the given bucket only when no buckets exist yet. Returns whether it was inserted. */
  def insertIfNoneExist(config: ResourceBucketConfig): IO[Boolean] =
    pool.use(_.prepare(queries.insertIfNoneExist).flatMap(_.execute(BucketRow.fromConfig(config)))).map {
      case skunk.data.Completion.Insert(count) => count > 0
      case _                                   => false
    }

  /** Updates the bucket, unless it was changed since `expectedUpdatedAt` (optimistic locking). */
  def update(config: ResourceBucketConfig, expectedUpdatedAt: Instant): IO[Either[BucketError, StoredBucket]] =
    val row      = BucketRow.fromConfig(config)
    val bucketId = BucketId(config.id)
    pool.use: s =>
      s.transaction.use: _ =>
        for
          maybeUpdatedAt <- s.prepare(queries.update).flatMap(_.option((row.required_role, row.settings, row.bucket_id, expectedUpdatedAt)))
          result         <- maybeUpdatedAt match
                              case Some(updatedAt) => IO.pure(Right(StoredBucket(config, updatedAt)))
                              case None            =>
                                for
                                  exists <- s.prepare(queries.exists).flatMap(_.unique(bucketId))
                                yield Left(if exists then BucketError.Modified(bucketId) else BucketError.NotFound(bucketId))
        yield result

  /**
   * Deletes the bucket together with all its resources (and their tags and collection memberships) from the database.
   * Unless forced, a bucket that still contains resources is not deleted. Returns the number of deleted resources.
   */
  def transact[A](f: Session[IO] => IO[A]): IO[A] = pool.use(s => s.transaction.use(_ => f(s)))

  /** The delete body, run on the caller's session so it can be composed with other work in a single transaction. */
  def deleteWith(s: Session[IO], bucketId: BucketId, force: Boolean): IO[Either[BucketError, Long]] =
    (for
      _     <- EitherT.fromOptionF(s.prepare(queries.lockForDelete).flatMap(_.option(bucketId)), BucketError.NotFound(bucketId))
      count <- EitherT.liftF(s.prepare(queries.resourceCount).flatMap(_.unique(bucketId)))
      _     <- EitherT.cond[IO](force || count == 0, (), BucketError.NotEmpty(bucketId, count))
      _     <- EitherT.liftF(deleteResources(s, bucketId))
      _     <- EitherT.liftF(s.prepare(queries.delete).flatMap(_.execute(bucketId)))
    yield count).value

  def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Long]] =
    transact(s => deleteWith(s, bucketId, force))

  /** Deletes all resources of a bucket, e.g. those added by a sync that was still running while the bucket was deleted. */
  def deleteResources(bucketId: BucketId): IO[Unit] =
    pool.use(s => s.transaction.use(_ => deleteResources(s, bucketId)))

  private def deleteResources(s: Session[IO], bucketId: BucketId): IO[Unit] =
    for
      _ <- s.prepare(queries.deleteResourceTags).flatMap(_.execute(bucketId))
      _ <- s.prepare(queries.deleteResources).flatMap(_.execute(bucketId))
    yield ()
