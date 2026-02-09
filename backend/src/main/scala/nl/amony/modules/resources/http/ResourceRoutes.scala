package nl.amony.modules.resources.http

import ApiError.NotFound
import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{Resource, ResourceBucket, ResourceId}

def oneOfList[T](variants: List[OneOfVariant[? <: T]]) = EndpointOutput.OneOf[T, T](variants, Mapping.id)

enum ApiError:
  case NotFound, BadRequest

val apiErrorOutputs = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(ApiError.NotFound),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.BadRequest))(ApiError.BadRequest)
)

val errorOutput: EndpointOutput[ApiError | SecurityError] = oneOfList(securityErrors ++ apiErrorOutputs)

object ResourceRoutes:

  private val apiNoCacheHeaders: EndpointOutput[Unit] = List(
    header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
    header(HeaderNames.Pragma, "no-cache"),
    header(HeaderNames.Expires, "0")
  ).reduce(_ and _)

  given Codec[String, ResourceId, TextPlain] = Codec.string.mapDecode(s => DecodeResult.Value(ResourceId.apply(s)))(identity)

  val getBuckets =
    endpoint
      .name("getBuckets").tag("resources").description("Get information about a resource by its id")
      .get.in("api" / "buckets")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[BucketDto]])

  val getResourceById: Endpoint[SecurityInput, (String, ResourceId), ApiError | SecurityError, ResourceDto, Any] =
    endpoint
      .name("getResourceById").tag("resources").description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[ResourceDto])

  val updateUserMetaData: Endpoint[SecurityInput, (String, ResourceId, UserMetaDto), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("updateUserMetaData").tag("resources").description("Update the user metadata of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto]).errorOut(errorOutput)

  val updateThumbnailTimestamp: Endpoint[SecurityInput, (String, ResourceId, ThumbnailTimestampDto), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("updateThumbnailTimestamp").tag("resources").description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto]).errorOut(errorOutput)

  val deleteResource: Endpoint[SecurityInput, (String, ResourceId), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("deleteResource").tag("resources").description("Delete a resource by its id")
      .delete.in("api" / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)

  val modifyTagsBulk: Endpoint[SecurityInput, (String, BulkTagsUpdateDto), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("modifyResourceTagsBulk").tag("resources").description("Add or remove tags for multiple resources")
      .post.in("api" / "resources" / path[String]("bucketId") / "bulk" / "tags").securityIn(securityInput)
      .in(jsonBody[BulkTagsUpdateDto])
      .errorOut(errorOutput)

  val endpoints = List(getResourceById, deleteResource, updateUserMetaData, updateThumbnailTimestamp, modifyTagsBulk)

  def apply(buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: ResourceId): EitherT[IO, ApiError, (ResourceBucket, Resource)] =
      for
        bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
        resource <- EitherT.fromOptionF(bucket.getResource(resourceId), NotFound)
      yield bucket -> resource

    val bucketList = buckets.values.toList

    def sanitize(input: String, maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, String] =
      for
        _      <- EitherT.cond[IO](input.length <= maxLength, (), ApiError.BadRequest)
        _      <- EitherT.cond[IO](input.forall(characterAllowFn), (), ApiError.BadRequest)
        trimmed = input.trim
        _      <- EitherT.cond[IO](trimmed == Jsoup.clean(trimmed, Safelist.basic), (), ApiError.BadRequest)
      yield trimmed

    def sanitizeOpt(input: Option[String], maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, Option[String]] =
      input.map(sanitize(_, maxLength, characterAllowFn).map(Some(_))).getOrElse(EitherT.rightT[IO, ApiError](None))

    def sanitizeTags(tags: List[String]): EitherT[IO, ApiError, List[String]] = tags.map(sanitize(_, 64, _.isLetterOrDigit)).sequence

    val getBucketsImpl = getBuckets.serverSecurityLogicPure(apiSecurity.publicEndpoint)
      .serverLogicSuccess(_ => _ => IO.pure(buckets.values.map(bucket => BucketDto(bucket.id, "", "")).toList))

    val getResourceByIdImpl = getResourceById.serverSecurityLogicPure(apiSecurity.publicEndpoint)
      .serverLogic(_ => (bucketId, resourceId) => getResource(bucketId, resourceId).map((_, resource) => toDto(resource.info)).value)

    val updateUserMetaDataImpl = updateUserMetaData.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogic {
      _ => (bucketId, resourceId, userMeta) =>

        (for
          sanitizedTitle       <- sanitizeOpt(userMeta.title, 128, _ => true)
          sanitizedDescription <- sanitizeOpt(userMeta.description, 1280, _ => true)
          sanitizedTags        <- sanitizeTags(userMeta.tags)
          response             <- getResource(bucketId, resourceId)
          (bucket, _)           = response
          _                    <- EitherT.right(bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags))
        yield ()).value
    }

    val updateThumbnailTimestampImpl =
      updateThumbnailTimestamp.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin))
        .serverLogic(_ =>
          (bucketId, resourceId, dto) =>
            getResource(bucketId, resourceId).flatMap((bucket, _) =>
              EitherT.right(bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis))
            )
              .value
        )

    val deleteResourceImpl = deleteResource.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin))
      .serverLogic(_ =>
        (bucketId, resourceId) =>
          (for
            bucket <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
            _      <- EitherT.right(bucket.deleteResource(resourceId))
          yield ()).value
      )

    val modifyTagsBulkImpl = modifyTagsBulk.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogic {
      _ => (bucketId, dto) =>
        val action =
          for
            _               <- EitherT.cond[IO](dto.ids.nonEmpty, (), ApiError.BadRequest)
            sanitizedIds     = dto.ids.distinct.toSet.map(ResourceId(_))
            sanitizedAdd    <- sanitizeTags(dto.tagsToAdd).map(_.toSet)
            sanitizedRemove <- sanitizeTags(dto.tagsToRemove).map(_.toSet)
            bucket          <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
            _               <- EitherT.right(bucket.modifyTags(sanitizedIds, sanitizedAdd, sanitizedRemove))
          yield ()
        action.value
    }

    Http4sServerInterpreter[IO](serverOptions)
      .toRoutes(List(getResourceByIdImpl, deleteResourceImpl, updateUserMetaDataImpl, updateThumbnailTimestampImpl, modifyTagsBulkImpl))
  }
