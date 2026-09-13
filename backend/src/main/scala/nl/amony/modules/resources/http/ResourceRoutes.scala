package nl.amony.modules.resources.http

import ApiError.NotFound
import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.lib.tapir.dsl.{RoutesModule, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{Resource, ResourceBucket, ResourceId, UploadError}

val errorOutput: EndpointOutput[ApiError | SecurityError] = oneOfList(securityErrors ++ apiErrorOutputs)

object ResourceRoutes extends RoutesModule:

  val getBuckets =
    register(endpoint
      .name("getBuckets").tag("resources").description("Get information about a resource by its id")
      .get.in("api" / "buckets")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[BucketDto]]))

  val getResourceById: Endpoint[SecurityInput, (String, ResourceId), ApiError | SecurityError, ResourceDto, Any] =
    register(endpoint
      .name("getResourceById").tag("resources").description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[ResourceDto]))

  val updateUserMetaData: Endpoint[SecurityInput, (String, ResourceId, UserMetaDto), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("updateUserMetaData").tag("resources").description("Update the user metadata of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto]).errorOut(errorOutput))

  val updateThumbnailTimestamp: Endpoint[SecurityInput, (String, ResourceId, ThumbnailTimestampDto), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("updateThumbnailTimestamp").tag("resources").description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto]).errorOut(errorOutput))

  val deleteResource: Endpoint[SecurityInput, (String, ResourceId), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("deleteResource").tag("resources").description("Delete a resource by its id")
      .delete.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput))

  val modifyTagsBulk: Endpoint[SecurityInput, (String, BulkTagsUpdateDto), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("modifyResourceTagsBulk").tag("resources").description("Add or remove tags for multiple resources")
      .post.in("api" / "resources" / path[String]("bucketId") / "bulk" / "tags").securityIn(securityInput)
      .in(jsonBody[BulkTagsUpdateDto])
      .errorOut(errorOutput))

  val uploadResource: Endpoint[SecurityInput, (String, String, fs2.Stream[IO, Byte]), ApiError | SecurityError, ResourceDto, Fs2Streams[IO]] =
    register(endpoint
      .name("uploadResource").tag("resources").description("Upload a resource to a bucket")
      .post.in("api" / "resources" / path[String]("bucketId") / "upload")
      .securityIn(securityInput).errorOut(errorOutput)
      .in(header[String]("X-Filename"))
      .in(streamBody(Fs2Streams[IO])(Schema.schemaForByteArray, CodecFormat.OctetStream()))
      .out(jsonBody[ResourceDto]))

  def apply(buckets: Map[String, ResourceBucket])(
    using serverOptions: Http4sServerOptions[IO],
    apiSecurity: ApiSecurity
  ): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: ResourceId): EitherT[IO, ApiError, (ResourceBucket, Resource)] =
      for
        bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
        resource <- EitherT.fromOptionF(bucket.getResource(resourceId), NotFound)
      yield bucket -> resource

    def mapUploadError(uploadError: UploadError): ApiError | SecurityError =
      uploadError match
        case UploadError.InvalidFileName(_) => ApiError.BadRequest
        case UploadError.StorageError(_)    => ApiError.BadRequest

    routes[IO](serverOptions) {
      serverLogicT(endpoint = getResourceById, authorize = apiSecurity.publicEndpoint) { _ => (bucketId, resourceId) =>
        getResource(bucketId, resourceId).map((_, resource) => toDto(resource.info))
      }

      serverLogicT(endpoint = deleteResource, requiredRole = Role.Admin) { _ => (bucketId, resourceId) =>
        for
          bucket <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
          _      <- EitherT.right(bucket.deleteResource(resourceId))
        yield ()
      }

      serverLogicT(endpoint = updateUserMetaData, requiredRole = Role.Admin) { _ => (bucketId, resourceId, userMeta) =>
        for
          sanitizedTitle       <- sanitizeOpt(userMeta.title, 128, _ => true)
          sanitizedDescription <- sanitizeOpt(userMeta.description, 1280, _ => true)
          sanitizedTags        <- sanitizeTags(userMeta.tags)
          response             <- getResource(bucketId, resourceId)
          (bucket, _)           = response
          _                    <- EitherT.right(bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags))
        yield ()
      }

      serverLogicT(endpoint = updateThumbnailTimestamp, requiredRole = Role.Admin) { _ => (bucketId, resourceId, dto) =>
        getResource(bucketId, resourceId).flatMap((bucket, _) =>
          EitherT.right(bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis))
        )
      }

      serverLogicT(endpoint = modifyTagsBulk, requiredRole = Role.Admin) { _ => (bucketId, dto) =>
        val action =
          for
            _               <- EitherT.cond[IO](dto.ids.nonEmpty, (), ApiError.BadRequest)
            sanitizedIds     = dto.ids.distinct.toSet.map(ResourceId(_))
            sanitizedAdd    <- sanitizeTags(dto.tagsToAdd).map(_.toSet)
            sanitizedRemove <- sanitizeTags(dto.tagsToRemove).map(_.toSet)
            bucket          <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
            _               <- EitherT.right(bucket.updateResourceTags(sanitizedIds, sanitizedAdd, sanitizedRemove))
          yield ()
        action
      }

      serverLogicT(endpoint = uploadResource, requiredRole = Role.Admin) { token => (bucketId, fileName, body) =>
        for
          bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound: ApiError | SecurityError)
          resource <- EitherT(bucket.uploadResource(token.userId, fileName, body)).leftMap(mapUploadError)
        yield toDto(resource)
      }

      serverLogic(endpoint = getBuckets, authorize = apiSecurity.publicEndpoint) { _ => _ =>
        IO.pure(Right(buckets.values.map(bucket => BucketDto(bucket.id, "", "")).toList))
      }
    }
  }
