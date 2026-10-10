package nl.amony.modules.admin

import java.nio.file.{InvalidPathException, Path}
import java.time.Instant
import scala.concurrent.duration.DurationLong

import cats.data.EitherT
import cats.effect.IO
import io.circe.Codec
import scribe.Logging
import sttp.tapir.*
import sttp.tapir.json.circe.*

import nl.amony.lib.tapir.apiNoCacheHeaders
import nl.amony.lib.tapir.dsl.error.{BadRequestError, ConflictError, ErrorResponse, NotFoundError, SecurityError}
import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.*

case class ScanSettingsDto(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
  pollIntervalSeconds: Long,
  includePatterns: List[String],
  excludePatterns: List[String]
) derives Codec, Schema

case class BucketConfigDto(
  id: String,
  `type`: String,
  requiredRole: Option[String],
  path: String,
  relativeUploadPath: String,
  generatePreviewsOnAdd: Boolean,
  hashingAlgorithm: Option[String],
  sync: ScanSettingsDto,
  updatedAt: Option[Instant]
) derives Codec, Schema

object BucketConfigDto:

  val localDirectoryType = "LocalDirectory"

  def fromStored(stored: StoredBucket): BucketConfigDto = stored.config match
    case c: LocalDirectoryConfig =>
      BucketConfigDto(
        id                    = c.id,
        `type`                = localDirectoryType,
        requiredRole          = c.requiredRole.map(_.toString),
        path                  = c.path.toString,
        relativeUploadPath    = c.relativeUploadPath.toString,
        generatePreviewsOnAdd = c.generatePreviewsOnAdd,
        hashingAlgorithm      = Some(c.hashingAlgorithm.name),
        sync                  = ScanSettingsDto(
          enabled             = c.sync.enabled,
          syncOnStartup       = c.sync.syncOnStartup,
          newFilesOwner       = c.sync.newFilesOwner,
          pollIntervalSeconds = c.sync.pollInterval.toSeconds,
          includePatterns     = c.sync.includePatterns,
          excludePatterns     = c.sync.excludePatterns
        ),
        updatedAt             = Some(stored.updatedAt)
      )

  private def parsePath(field: String, value: String): Either[String, Path] =
    try Right(Path.of(value.strip()))
    catch case _: InvalidPathException => Left(s"Invalid $field: '$value'")

  def toConfig(dto: BucketConfigDto): Either[String, ResourceBucketConfig] =
    dto.`type` match
      case `localDirectoryType` =>
        for
          path       <- parsePath("path", dto.path)
          uploadPath <- parsePath("upload path", dto.relativeUploadPath)
          algorithm  <- dto.hashingAlgorithm match
                          case None       => Right(PartialHash)
                          case Some(name) => HashingAlgorithm.fromName(name).toRight(s"Unknown hashing algorithm: $name")
        yield LocalDirectoryConfig(
          id                    = dto.id.strip(),
          requiredRole          = dto.requiredRole.map(_.strip()).filter(_.nonEmpty).map(Role(_)),
          path                  = path,
          sync                  = ScanConfig(
            enabled         = dto.sync.enabled,
            syncOnStartup   = dto.sync.syncOnStartup,
            newFilesOwner   = dto.sync.newFilesOwner.strip(),
            pollInterval    = dto.sync.pollIntervalSeconds.seconds,
            includePatterns = dto.sync.includePatterns.map(_.strip()).filter(_.nonEmpty),
            excludePatterns = dto.sync.excludePatterns.map(_.strip()).filter(_.nonEmpty)
          ),
          hashingAlgorithm      = algorithm,
          relativeUploadPath    = uploadPath,
          generatePreviewsOnAdd = dto.generatePreviewsOnAdd
        )
      case other                => Left(s"Unknown bucket type: $other")

object BucketAdminRoutes extends RoutesModule, Logging:

  type BucketAdminError = SecurityError | NotFoundError | BadRequestError | ConflictError

  private val errorOutput: EndpointOutput[BucketAdminError] =
    ErrorResponse.of[SecurityError, NotFoundError, BadRequestError, ConflictError]

  val listBuckets =
    register(endpoint.name("adminListBuckets").tag("admin").description("Get the configuration of all buckets")
      .get.in("api" / "admin" / "buckets")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[BucketConfigDto]]))

  val getBucket =
    register(endpoint.name("adminGetBucket").tag("admin").description("Get the configuration of a bucket")
      .get.in("api" / "admin" / "buckets" / path[BucketId]("bucketId"))
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[BucketConfigDto]))

  val createBucket =
    register(endpoint.name("adminCreateBucket").tag("admin").description("Create a new bucket")
      .post.in("api" / "admin" / "buckets")
      .in(jsonBody[BucketConfigDto])
      .securityIn(securityInput).errorOut(errorOutput)
      .out(jsonBody[BucketConfigDto]))

  val updateBucket =
    register(endpoint.name("adminUpdateBucket").tag("admin")
      .description(
        "Update the configuration of a bucket. The id, type and hashing algorithm cannot be changed. The bucket is restarted. " +
          "Files that no longer match the include/exclude patterns are removed from the index, including their tags and metadata."
      )
      .put.in("api" / "admin" / "buckets" / path[BucketId]("bucketId"))
      .in(jsonBody[BucketConfigDto])
      .securityIn(securityInput).errorOut(errorOutput)
      .out(jsonBody[BucketConfigDto]))

  val deleteBucket =
    register(endpoint.name("adminDeleteBucket").tag("admin")
      .description(
        "Delete a bucket. Refused when the bucket still contains resources, unless forced. " +
          "Forcing removes the resources from the database and search index, media files are never deleted."
      )
      .delete.in("api" / "admin" / "buckets" / path[BucketId]("bucketId"))
      .in(query[Option[Boolean]]("force").description("Also delete the resources of the bucket from the database and search index."))
      .securityIn(securityInput).errorOut(errorOutput))

  private def toApiError(error: BucketError): BucketAdminError = error match
    case BucketError.NotFound(id)        => NotFoundError("bucket_not_found", s"Bucket '$id' not found")
    case BucketError.AlreadyExists(id)   => ConflictError("bucket_exists", s"Bucket '$id' already exists")
    case BucketError.Modified(id)        =>
      ConflictError("bucket_modified", s"Bucket '$id' was changed by someone else in the meantime, reload it and try again")
    case BucketError.Invalid(message)    => BadRequestError("invalid_bucket", message)
    case BucketError.NotEmpty(id, count) =>
      ConflictError("bucket_not_empty", s"Bucket '$id' contains $count resources, use force to delete it anyway")

  private def requireUpdatedAt(dto: BucketConfigDto): EitherT[IO, BucketAdminError, Instant] =
    EitherT.fromOption[IO](dto.updatedAt, BadRequestError("invalid_bucket", "The updatedAt of the bucket is required for an update"))

  private def parseDto(dto: BucketConfigDto): EitherT[IO, BucketAdminError, ResourceBucketConfig] =
    EitherT.fromEither[IO](BucketConfigDto.toConfig(dto)).leftMap(BadRequestError("invalid_bucket", _))

  def apply(registry: BucketRegistry)(using apiSecurity: ApiSecurity): ServerEndpoints[IO] =

    def requireConfig(bucketId: BucketId): EitherT[IO, BucketAdminError, StoredBucket] =
      EitherT.fromOptionF(registry.getConfig(bucketId), toApiError(BucketError.NotFound(bucketId)))

    routes[IO] {
      serverLogic(endpoint = listBuckets, requiredPermission = Permission.Admin) { _ => _ =>
        registry.allConfigs.map(configs => Right(configs.map(BucketConfigDto.fromStored)))
      }

      serverLogicT(endpoint = getBucket, requiredPermission = Permission.Admin) { _ => bucketId =>
        requireConfig(bucketId).map(BucketConfigDto.fromStored)
      }

      serverLogicT(endpoint = createBucket, requiredPermission = Permission.Admin) { _ => dto =>
        for
          config <- parseDto(dto)
          _      <- EitherT(registry.create(config)).leftMap(toApiError)
          stored <- requireConfig(BucketId(config.id))
        yield BucketConfigDto.fromStored(stored)
      }

      serverLogicT(endpoint = updateBucket, requiredPermission = Permission.Admin) { _ => (bucketId, dto) =>
        for
          config    <- parseDto(dto)
          updatedAt <- requireUpdatedAt(dto)
          _         <- EitherT.cond[IO](config.id == bucketId, (), BadRequestError("invalid_bucket", "The bucket id cannot be changed"))
          _         <- EitherT(registry.update(config, updatedAt)).leftMap(toApiError)
          stored    <- requireConfig(bucketId)
        yield BucketConfigDto.fromStored(stored)
      }

      serverLogicT(endpoint = deleteBucket, requiredPermission = Permission.Admin) { _ => (bucketId, force) =>
        EitherT(registry.delete(bucketId, force.getOrElse(false))).leftMap(toApiError)
      }
    }
