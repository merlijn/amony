package nl.amony.modules.resources.http

import java.net.URI
import java.nio.file.{InvalidPathException, Path}
import java.time.Instant
import scala.concurrent.duration.DurationLong

import cats.data.EitherT
import cats.effect.IO
import io.circe.Codec
import scribe.Logging
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.json.circe.*

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.*
import nl.amony.platform.tapir.apiNoCacheHeaders
import nl.amony.platform.tapir.error.{BadRequestError, ConflictError, ErrorResponse, NotFoundError, SecurityError}
import nl.amony.platform.tapir.{RoutesModule, ServerEndpoints, routes}

case class ScanSettingsDto(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
  pollIntervalSeconds: Long,
  includePatterns: List[String],
  excludePatterns: List[String]
) derives Codec, Schema

case class LocalDirectoryStorageDto(
  path: String,
  relativeUploadPath: String,
  sync: ScanSettingsDto
) derives Codec, Schema

case class S3StorageDto(
  bucket: String,
  region: String,
  endpoint: Option[String]            = None,
  prefix: Option[String]              = None,
  pathStyleAccess: Boolean            = false,
  presignedUrlTtlSeconds: Long        = 900L,
  accessKeyId: Option[String]         = None,
  secretAccessKey: Option[String]     = None,
  hasSecretAccessKey: Option[Boolean] = None
) derives Codec, Schema

case class BucketConfigDto(
  id: String,
  `type`: String,
  requiredRole: Option[String],
  generatePreviewsOnAdd: Boolean,
  localDirectory: Option[LocalDirectoryStorageDto] = None,
  s3: Option[S3StorageDto]                         = None,
  updatedAt: Option[Instant]                       = None
) derives Codec, Schema

object BucketConfigDto:

  val localDirectoryType = "LocalDirectory"
  val s3Type             = "S3"

  def fromStored(stored: StoredBucket): BucketConfigDto = stored.config.storage match
    case local: LocalDirectoryStorageConfig =>
      BucketConfigDto(
        id                    = stored.config.id,
        `type`                = localDirectoryType,
        requiredRole          = stored.config.requiredRole.map(_.toString),
        generatePreviewsOnAdd = stored.config.generatePreviewsOnAdd,
        localDirectory        = Some(
          LocalDirectoryStorageDto(
            path               = local.path.toString,
            relativeUploadPath = local.relativeUploadPath.toString,
            sync               = ScanSettingsDto(
              enabled             = local.sync.enabled,
              syncOnStartup       = local.sync.syncOnStartup,
              newFilesOwner       = local.sync.newFilesOwner,
              pollIntervalSeconds = local.sync.pollInterval.toSeconds,
              includePatterns     = local.sync.includePatterns,
              excludePatterns     = local.sync.excludePatterns
            )
          )
        ),
        updatedAt             = Some(stored.updatedAt)
      )
    case s3: S3StorageConfig                =>
      BucketConfigDto(
        id                    = stored.config.id,
        `type`                = s3Type,
        requiredRole          = stored.config.requiredRole.map(_.toString),
        generatePreviewsOnAdd = stored.config.generatePreviewsOnAdd,
        s3                    = Some(
          S3StorageDto(
            bucket                 = s3.bucket,
            region                 = s3.region,
            endpoint               = s3.endpoint.map(_.toString),
            prefix                 = s3.prefix,
            pathStyleAccess        = s3.pathStyleAccess,
            presignedUrlTtlSeconds = s3.presignedUrlTtl.toSeconds,
            accessKeyId            = s3.accessKeyId,
            secretAccessKey        = None,
            hasSecretAccessKey     = Some(s3.secretAccessKey.isDefined)
          )
        ),
        updatedAt             = Some(stored.updatedAt)
      )

  private def parsePath(field: String, value: String): Either[String, Path] =
    try Right(Path.of(value.strip()))
    catch case _: InvalidPathException => Left(s"Invalid $field: '$value'")

  private def parseOptionalUri(field: String, value: Option[String]): Either[String, Option[URI]] =
    value.map(_.strip()).filter(_.nonEmpty) match
      case None    => Right(None)
      case Some(s) =>
        try Right(Some(URI.create(s)))
        catch case _: IllegalArgumentException => Left(s"Invalid $field: '$s'")

  def toConfig(dto: BucketConfigDto): Either[String, ResourceBucketConfig] =
    val requiredRole = dto.requiredRole.map(_.strip()).filter(_.nonEmpty).map(Role(_))
    dto.`type` match
      case `localDirectoryType` =>
        for
          local      <- dto.localDirectory.toRight("LocalDirectory storage settings are required")
          path       <- parsePath("path", local.path)
          uploadPath <- parsePath("upload path", local.relativeUploadPath)
        yield ResourceBucketConfig(
          id                    = dto.id.strip(),
          requiredRole          = requiredRole,
          generatePreviewsOnAdd = dto.generatePreviewsOnAdd,
          storage               = LocalDirectoryStorageConfig(
            path               = path,
            sync               = ScanConfig(
              enabled         = local.sync.enabled,
              syncOnStartup   = local.sync.syncOnStartup,
              newFilesOwner   = local.sync.newFilesOwner.strip(),
              pollInterval    = local.sync.pollIntervalSeconds.seconds,
              includePatterns = local.sync.includePatterns.map(_.strip()).filter(_.nonEmpty),
              excludePatterns = local.sync.excludePatterns.map(_.strip()).filter(_.nonEmpty)
            ),
            relativeUploadPath = uploadPath
          )
        )
      case `s3Type`             =>
        for
          s3       <- dto.s3.toRight("S3 storage settings are required")
          endpoint <- parseOptionalUri("endpoint", s3.endpoint)
        yield ResourceBucketConfig(
          id                    = dto.id.strip(),
          requiredRole          = requiredRole,
          generatePreviewsOnAdd = dto.generatePreviewsOnAdd,
          storage               = S3StorageConfig(
            bucket          = s3.bucket.strip(),
            region          = s3.region.strip(),
            endpoint        = endpoint,
            prefix          = s3.prefix.map(_.strip()).filter(_.nonEmpty),
            pathStyleAccess = s3.pathStyleAccess,
            presignedUrlTtl = s3.presignedUrlTtlSeconds.seconds,
            accessKeyId     = s3.accessKeyId.map(_.strip()).filter(_.nonEmpty),
            secretAccessKey = s3.secretAccessKey.map(_.strip()).filter(_.nonEmpty)
          )
        )
      case other                => Left(s"Unknown bucket type: $other")

private[resources] object BucketRoutes extends RoutesModule, Logging:

  case object NdJson extends CodecFormat:
    override val mediaType: sttp.model.MediaType = sttp.model.MediaType.unsafeParse("application/x-ndjson")

  type BucketAdminError  = SecurityError | NotFoundError | BadRequestError | ConflictError
  type BucketActionError = SecurityError | NotFoundError

  private val errorOutput: EndpointOutput[BucketAdminError] =
    ErrorResponse.of[SecurityError, NotFoundError, BadRequestError, ConflictError]

  private val actionErrorOutput: EndpointOutput[BucketActionError] =
    ErrorResponse.of[SecurityError, NotFoundError]

  private val getBuckets =
    register(endpoint.name("getBuckets").tag("buckets")
      .description(
        "Get the buckets the current user can see. The configuration of each bucket is only included for users allowed to manage buckets."
      )
      .get.in("api" / "buckets")
      .securityIn(securityInput).errorOut(ErrorResponse.standardErrorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[BucketDto]]))

  private val getBucket =
    register(endpoint.name("adminGetBucket").tag("buckets").description("Get the configuration of a bucket")
      .get.in("api" / "buckets" / path[BucketId]("bucketId"))
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[BucketConfigDto]))

  private val createBucket =
    register(endpoint.name("adminCreateBucket").tag("buckets").description("Create a new bucket")
      .post.in("api" / "buckets")
      .in(jsonBody[BucketConfigDto])
      .securityIn(securityInput).errorOut(errorOutput)
      .out(jsonBody[BucketConfigDto]))

  private val updateBucket =
    register(endpoint.name("adminUpdateBucket").tag("buckets")
      .description(
        "Update the configuration of a bucket. The id, type and hashing algorithm cannot be changed. The bucket is restarted. " +
          "Files that no longer match the include/exclude patterns are removed from the index, including their tags and metadata."
      )
      .put.in("api" / "buckets" / path[BucketId]("bucketId"))
      .in(jsonBody[BucketConfigDto])
      .securityIn(securityInput).errorOut(errorOutput)
      .out(jsonBody[BucketConfigDto]))

  private val deleteBucket =
    register(endpoint.name("adminDeleteBucket").tag("buckets")
      .description(
        "Delete a bucket. Refused when the bucket still contains resources, unless forced. " +
          "Forcing removes the resources from the database and search index, media files are never deleted."
      )
      .delete.in("api" / "buckets" / path[BucketId]("bucketId"))
      .in(query[Option[Boolean]]("force").description("Also delete the resources of the bucket from the database and search index."))
      .securityIn(securityInput).errorOut(errorOutput))

  private val refresh =
    register(endpoint.tag("buckets").name("adminRefreshBucket").description("Refresh all resources in a bucket")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "refresh")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val reIndex =
    register(endpoint.tag("buckets").name("adminReindexBucket").description("Re-index all resources in a bucket.")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "reindex")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val rescanMetaData =
    register(endpoint
      .tag("buckets").name("adminRescanMetaData").description("Rescan the metadata of all files in a bucket")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "rescan-metadata")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val reComputeHashes =
    register(endpoint.name("adminReComputeHashes").tag("buckets").description("Recompute the hashes of all files in a bucket")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "recompute-hashes")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val generatePreviews =
    register(endpoint.name("adminGeneratePreviews").tag("buckets")
      .description("Generate all configured thumbnails and preview clips for all resources in a bucket")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "generate-previews")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val fixNonStreamable =
    register(endpoint.name("adminFixNonStreamable").tag("buckets")
      .description("Remux all non-streamable resources in a bucket so they can be streamed progressively")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "fix-non-streamable")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private val exportBucket =
    register(endpoint.name("adminExportBucket").tag("buckets").description("Export all resources in a bucket")
      .get.in("api" / "buckets" / path[BucketId]("bucketId") / "export")
      .securityIn(securityInput)
      .out(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .errorOut(actionErrorOutput))

  private val importBucket =
    register(endpoint.name("adminImportBucket").tag("buckets").description("Import all resources in a bucket")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "import")
      .in(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  private def notFound(bucketId: BucketId): NotFoundError =
    NotFoundError("bucket_not_found", s"Bucket '$bucketId' not found")

  private def toApiError(error: BucketError): BucketAdminError = error match
    case BucketError.NotFound(id)        => notFound(id)
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

  private def preserveS3Secret(updated: ResourceBucketConfig, existing: Option[ResourceBucketConfig]): ResourceBucketConfig =
    (updated.storage, existing.map(_.storage)) match
      case (newS3: S3StorageConfig, Some(oldS3: S3StorageConfig))
          if newS3.accessKeyId.isDefined && newS3.secretAccessKey.isEmpty =>
        updated.copy(storage = newS3.copy(secretAccessKey = oldS3.secretAccessKey))
      case _ => updated

  def apply(registry: BucketRegistry, discovery: BucketDiscoveryRegistry, resourceIndex: ResourceIndex)(
    using apiSecurity: ApiSecurity
  ): ServerEndpoints[IO] =

    def requireConfig(bucketId: BucketId): EitherT[IO, BucketAdminError, StoredBucket] =
      EitherT.fromOptionF(registry.getConfig(bucketId), toApiError(BucketError.NotFound(bucketId)))

    def withBucket[O](bucketId: BucketId)(fn: ResourceBucket => EitherT[IO, BucketActionError, O]): EitherT[IO, BucketActionError, O] =
      EitherT.fromOptionF(registry.get(bucketId), notFound(bucketId)).flatMap(fn)

    routes[IO] {
      securedServerLogic(endpoint = getBuckets, requiredPermission = Permission.ViewResource) { auth => _ =>
        if auth.hasPermission(Permission.ManageBuckets) then
          registry.allConfigs.map(configs =>
            Right(configs.map(stored => BucketDto(stored.config.id, Some(BucketConfigDto.fromStored(stored)))))
          )
        else
          registry.all.map(buckets =>
            Right(buckets.filter(bucket => auth.canAccessBucket(bucket.requiredRole)).map(bucket => BucketDto(bucket.id)))
          )
      }

      securedServerLogicT(endpoint = getBucket, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        requireConfig(bucketId).map(BucketConfigDto.fromStored)
      }

      securedServerLogicT(endpoint = createBucket, requiredPermission = Permission.ManageBuckets) { _ => dto =>
        for
          config <- parseDto(dto)
          _      <- EitherT(registry.create(config)).leftMap(toApiError)
          stored <- requireConfig(BucketId(config.id))
        yield BucketConfigDto.fromStored(stored)
      }

      securedServerLogicT(endpoint = updateBucket, requiredPermission = Permission.ManageBuckets) { _ => (bucketId, dto) =>
        for
          parsed    <- parseDto(dto)
          updatedAt <- requireUpdatedAt(dto)
          _         <- EitherT.cond[IO](parsed.id == bucketId, (), BadRequestError("invalid_bucket", "The bucket id cannot be changed"))
          existing  <- EitherT.liftF(registry.getConfig(bucketId))
          config     = preserveS3Secret(parsed, existing.map(_.config))
          _         <- EitherT(registry.update(config, updatedAt)).leftMap(toApiError)
          stored    <- requireConfig(bucketId)
        yield BucketConfigDto.fromStored(stored)
      }

      securedServerLogicT(endpoint = deleteBucket, requiredPermission = Permission.ManageBuckets) { _ => (bucketId, force) =>
        EitherT(registry.delete(bucketId, force.getOrElse(false))).leftMap(toApiError)
      }

      securedServerLogicT(endpoint = refresh, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        EitherT.fromOptionF(discovery.getDiscovery(bucketId), notFound(bucketId)).flatMap { bucketDiscovery =>
          EitherT.liftF(
            IO(logger.info(s"Refreshing resources in bucket '$bucketId'")) >>
              bucketDiscovery.refresh() >>
              IO(logger.info(s"Finished refreshing resources in bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = reIndex, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(
            IO(logger.info(s"Re-indexing all resources in bucket '$bucketId'")) >>
              resourceIndex.reindex(bucket) >>
              IO(logger.info(s"Re-indexed all resources in bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = rescanMetaData, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(
            IO(logger.info(s"Re-scanning meta data of all resources in bucket '$bucketId'")) >>
              bucket.reScanAllMetadata() >>
              IO(logger.info(s"Finished re-scanning meta data of all resources in bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = reComputeHashes, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(
            IO(logger.info(s"Re-computing partialHashs of all resources in bucket '$bucketId'")) >>
              bucket.reComputePartialHashes() >>
              IO(logger.info(s"Finished re-computing partialHashs of all resources in bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = generatePreviews, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(
            IO(logger.info(s"Generating previews for all resources in bucket '$bucketId'")) >>
              bucket.generateAllPreviews() >>
              IO(logger.info(s"Finished generating previews for bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = fixNonStreamable, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(
            IO(logger.info(s"Normalizing non-streamable resources in bucket '$bucketId'")) >>
              resourceIndex.nonStreamable(bucketId)
                .evalMap { resourceId =>
                  bucket.fixStreamability(resourceId).flatMap {
                    case Right(_)    => IO.unit
                    case Left(error) => IO(logger.warn(s"Skipping '$resourceId' in bucket '$bucketId': $error"))
                  }
                }
                .compile.drain >>
              IO(logger.info(s"Finished normalizing non-streamable resources in bucket '$bucketId'"))
          )
        }
      }

      securedServerLogicT(endpoint = exportBucket, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        withBucket(bucketId) { bucket =>
          EitherT.liftF(IO(logger.info(s"Exporting resources in bucket '$bucketId'"))).map { _ =>
            bucket.getAllResources
              .map(resource => summon[io.circe.Codec[ResourceDto]].apply(toDto(resource)).noSpaces).intersperse("\n")
              .through(fs2.text.utf8.encode[IO])
          }
        }
      }

      securedServerLogicT(endpoint = importBucket, requiredPermission = Permission.ManageBuckets)(_ =>
        (bucketId, stream) =>
          withBucket(bucketId) { bucket =>
            val resources: fs2.Stream[IO, ResourceInfo] = stream.through(fs2.text.utf8.decode[IO]).through(fs2.text.lines)
              .map(line => io.circe.parser.decode[ResourceDto](line).map(_.toDomain())).flatMap {
                case Right(resource) => fs2.Stream.emit(resource)
                case Left(error)     => fs2.Stream.raiseError[IO](error)
              }

            EitherT.liftF(
              IO(logger.info(s"Importing resources into bucket '$bucketId'")) >>
                bucket.importBackup(resources)
            )
          }
      )
    }
