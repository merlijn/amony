package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import scribe.Logging
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.HeaderNames
import sttp.tapir.*
import sttp.tapir.json.circe.*

import nl.amony.lib.tapir.apiNoCacheHeaders
import nl.amony.lib.tapir.dsl.error.{BadRequestError, ErrorResponse, NotFoundError, SecurityError}
import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.BucketRegistry
import nl.amony.modules.resources.api.{BucketId, Resource, ResourceBucket, ResourceId, UploadError}
import nl.amony.modules.resources.local.LocalDirectoryBucket

val errorOutput: EndpointOutput[SecurityError | NotFoundError | BadRequestError] = ErrorResponse.standardErrorOutput

object ResourceRoutes extends RoutesModule, Logging:

  val getBuckets =
    register(endpoint
      .name("getBuckets").tag("resources").description("Get information about the buckets")
      .get.in("api" / "buckets")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[BucketDto]]))

  val getResourceById: Endpoint[SecurityInput, (BucketId, ResourceId), SecurityError | NotFoundError | BadRequestError, ResourceDto, Any] =
    register(endpoint
      .name("getResourceById").tag("resources").description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[ResourceDto]))

  val updateUserMetaData: Endpoint[SecurityInput, (BucketId, ResourceId, UserMetaDto), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("updateUserMetaData").tag("resources").description("Update the user metadata of a resource")
      .post.in("api" / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto]).errorOut(errorOutput))

  val updateThumbnailTimestamp
    : Endpoint[SecurityInput, (BucketId, ResourceId, ThumbnailTimestampDto), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("updateThumbnailTimestamp").tag("resources").description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto]).errorOut(errorOutput))

  val deleteResource: Endpoint[SecurityInput, (BucketId, ResourceId), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("deleteResource").tag("resources").description("Delete a resource by its id")
      .delete.in("api" / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput))

  val fixResourceStreamable: Endpoint[SecurityInput, (BucketId, ResourceId), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("fixResourceStreamable").tag("resources").description("Remux a non-streamable resource so it can be streamed progressively")
      .post.in("api" / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId") / "fix-streamability")
      .securityIn(securityInput).errorOut(errorOutput))

  val modifyTagsBulk: Endpoint[SecurityInput, (BucketId, BulkTagsUpdateDto), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("modifyResourceTagsBulk").tag("resources").description("Add or remove tags for multiple resources")
      .post.in("api" / "resources" / path[BucketId]("bucketId") / "bulk" / "tags").securityIn(securityInput)
      .in(jsonBody[BulkTagsUpdateDto])
      .errorOut(errorOutput))

  val uploadResource
    : Endpoint[
      SecurityInput,
      (BucketId, String, fs2.Stream[IO, Byte]),
      SecurityError | NotFoundError | BadRequestError,
      ResourceDto,
      Fs2Streams[IO]
    ] =
    register(endpoint
      .name("uploadResource").tag("resources").description("Upload a resource to a bucket")
      .post.in("api" / "resources" / path[BucketId]("bucketId") / "upload")
      .securityIn(securityInput).errorOut(errorOutput)
      .in(header[String]("X-Filename"))
      .in(streamBody(Fs2Streams[IO])(Schema.schemaForByteArray, CodecFormat.OctetStream()))
      .out(jsonBody[ResourceDto]))

  def apply(buckets: BucketRegistry)(
    using apiSecurity: ApiSecurity
  ): ServerEndpoints[IO] = {

    def getVisibleBucket(auth: AuthToken, bucketId: BucketId): EitherT[IO, NotFoundError, ResourceBucket] =
      EitherT.fromOptionF(
        buckets.get(bucketId).map(_.filter(bucket => apiSecurity.canAccessBucket(auth, bucket.requiredRole))),
        NotFoundError("not_found", "Resource not found")
      )

    def getResource(auth: AuthToken, bucketId: BucketId, resourceId: ResourceId): EitherT[IO, NotFoundError, (ResourceBucket, Resource)] =
      for
        bucket   <- getVisibleBucket(auth, bucketId)
        resource <- EitherT.fromOptionF(bucket.getResource(resourceId), NotFoundError("not_found", "Resource not found"))
      yield bucket -> resource

    def mapUploadError(uploadError: UploadError): BadRequestError =
      uploadError match
        case UploadError.InvalidFileName(message) => BadRequestError("invalid_file_name", message)
        case UploadError.StorageError(_)          => BadRequestError("upload_failed", "Failed to store the uploaded resource")

    routes[IO] {
      serverLogicT(endpoint = getResourceById, requiredPermission = Permission.ViewResource) { auth => (bucketId, resourceId) =>
        getResource(auth, bucketId, resourceId).map((_, resource) => toDto(resource.info))
      }

      serverLogicT(endpoint = deleteResource, requiredPermission = Permission.ManageResources) { auth => (bucketId, resourceId) =>
        for
          bucket <- getVisibleBucket(auth, bucketId)
          _      <- EitherT.right(bucket.deleteResource(resourceId))
        yield ()
      }

      serverLogic(endpoint = fixResourceStreamable, requiredPermission = Permission.Admin) { _ => (bucketId, resourceId) =>
        buckets.get(bucketId).flatMap:
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Normalizing resource '$resourceId' in bucket '$bucketId'")
            bucket.fixStreamability(resourceId).as(Right(()))
          case _                                  =>
            logger.info(s"Cannot normalize resource '$resourceId' in bucket '$bucketId'")
            IO.pure(Right(()))
      }

      serverLogicT(endpoint = updateUserMetaData, requiredPermission = Permission.ManageResources) { auth => (bucketId, resourceId, userMeta) =>
        for
          sanitizedTitle       <- sanitizeOpt(userMeta.title, 128, _ => true)
          sanitizedDescription <- sanitizeOpt(userMeta.description, 1280, _ => true)
          sanitizedTags        <- sanitizeTags(userMeta.tags)
          response             <- getResource(auth, bucketId, resourceId)
          (bucket, _)           = response
          _                    <- EitherT.right(bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags))
        yield ()
      }

      serverLogicT(endpoint = updateThumbnailTimestamp, requiredPermission = Permission.ManageResources) { auth => (bucketId, resourceId, dto) =>
        getResource(auth, bucketId, resourceId).flatMap((bucket, _) =>
          EitherT.right(bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis))
        )
      }

      serverLogicT(endpoint = modifyTagsBulk, requiredPermission = Permission.ManageResources) { auth => (bucketId, dto) =>
        for
          _               <- EitherT.cond[IO](dto.ids.nonEmpty, (), BadRequestError("no_resources", "At least one resource id is required"))
          sanitizedIds     = dto.ids.distinct.toSet.map(ResourceId(_))
          sanitizedAdd    <- sanitizeTags(dto.tagsToAdd).map(_.toSet)
          sanitizedRemove <- sanitizeTags(dto.tagsToRemove).map(_.toSet)
          bucket          <- getVisibleBucket(auth, bucketId)
          _               <- EitherT.right(bucket.updateResourceTags(sanitizedIds, sanitizedAdd, sanitizedRemove))
        yield ()
      }

      serverLogicT(endpoint = uploadResource, requiredPermission = Permission.UploadResource) { token => (bucketId, fileName, body) =>
        for
          bucket   <- getVisibleBucket(token, bucketId)
          resource <- EitherT(bucket.uploadResource(token.userId, fileName, body)).leftMap(mapUploadError)
        yield toDto(resource)
      }

      serverLogic(endpoint = getBuckets, requiredPermission = Permission.ViewResource) { auth => _ =>
        buckets.all.map(all =>
          Right(all.filter(bucket => apiSecurity.canAccessBucket(auth, bucket.requiredRole)).map(bucket => BucketDto(bucket.id, "", "")))
        )
      }
    }
  }
