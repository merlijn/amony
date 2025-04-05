package nl.amony.service.resources.web

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import nl.amony.lib.auth.*
import nl.amony.service.resources.web.ApiError.NotFound
import nl.amony.service.resources.web.dto.*
import nl.amony.service.resources.{Resource, ResourceBucket}
import org.http4s.HttpRoutes
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

def oneOfList[T](variants: List[OneOfVariant[_ <: T]]) = EndpointOutput.OneOf[T, T](variants, Mapping.id)

enum ApiError:
  case NotFound, BadRequest

val apiErrorOutputs = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(ApiError.NotFound),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.BadRequest))(ApiError.BadRequest),
)

val errorOutput: EndpointOutput[ApiError | SecurityError] = oneOfList(securityErrors ++ apiErrorOutputs)

object ResourceRoutes:

  private val apiCacheHeaders: EndpointOutput[Unit] = List(
    header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
    header(HeaderNames.Pragma, "no-cache"),
    header(HeaderNames.Expires, "0")
  ).reduce(_ and _)

  val getBuckets =
    endpoint
      .name("getBuckets")
      .tag("resources")
      .description("Get information about a resource by its id")
      .get.in("api" / "buckets")
      .securityIn(securityInput)
      .errorOut(errorOutput)
      .out(apiCacheHeaders)
      .out(jsonBody[List[BucketDto]])
  
  val getResourceById: Endpoint[SecurityInput, (String, String), ApiError | SecurityError, ResourceDto, Any] =
    endpoint
      .name("getResourceById")
      .tag("resources")
      .description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId"))
      .securityIn(securityInput)
      .errorOut(errorOutput)
      .out(apiCacheHeaders)
      .out(jsonBody[ResourceDto])

  val updateUserMetaData: Endpoint[SecurityInput, (String, String, UserMetaDto), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("updateUserMetaData")
      .tag("resources")
      .description("Update the user metadata of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto])
      .errorOut(errorOutput)
  
  val updateThumbnailTimestamp: Endpoint[SecurityInput, (String, String, ThumbnailTimestampDto), ApiError | SecurityError, Unit, Any] = 
    endpoint
      .name("updateThumbnailTimestamp")
      .tag("resources")
      .description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto])
      .errorOut(errorOutput)
    
  val endpoints = List(getResourceById, updateUserMetaData, updateThumbnailTimestamp)

  def apply(buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: String): EitherT[IO, ApiError, (ResourceBucket, Resource)] = 
      for {
        bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
        resource <- EitherT.fromOptionF(bucket.getResource(resourceId), NotFound)
      } yield bucket -> resource

    val getBucketsImpl =
      getBuckets
        .serverSecurityLogicPure(apiSecurity.publicEndpoint)
        .serverLogicSuccess(_ => _ => IO.pure(buckets.values.map(bucket => BucketDto(bucket.id, "", "")).toList))

    val getResourceByIdImpl =
      getResourceById
        .serverSecurityLogicPure(apiSecurity.publicEndpoint)
        .serverLogic(_ => (bucketId, resourceId) =>
          getResource(bucketId, resourceId).map((_, resource) => toDto(resource.info())).value
        )

    val updateUserMetaDataImpl =
      updateUserMetaData
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, userMeta) => {

          // TODO rewrite this using tapir Validators ?
          def sanitize(s: String, maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, String] =
            for {
              _ <- EitherT.cond[IO](s.length <= maxLength, (), ApiError.BadRequest)
              _ <- EitherT.cond[IO](s.forall(characterAllowFn), (), ApiError.BadRequest)
              trimmed = s.trim
              _ <- EitherT.cond[IO](trimmed == Jsoup.clean(trimmed, Safelist.basic), (), ApiError.BadRequest)
            } yield trimmed

          def sanitizeOpt(s: Option[String], maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, Option[String]] =
            s.map(sanitize(_, maxLength, characterAllowFn).map(Some(_))).getOrElse(EitherT.rightT[IO, ApiError](None))

          (for {
            sanitizedTitle       <- sanitizeOpt(userMeta.title, 128, _ => true)
            sanitizedDescription <- sanitizeOpt(userMeta.description, 1280, _ => true)
            sanitizedTags        <- userMeta.tags.map(tag => sanitize(tag, 64, c => c.isLetterOrDigit)).sequence
            response             <- getResource(bucketId, resourceId)
            (bucket, _)           = response
            _                    <- EitherT.right(bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags))
          } yield ()).value
        })
      
    val updateThumbnailTimestampImpl =
      updateThumbnailTimestamp
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, dto) =>
          getResource(bucketId, resourceId).flatMap {
            (bucket, _) => EitherT.right(bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis))
          }.value
        )

    Http4sServerInterpreter[IO](serverOptions)
      .toRoutes(List(getResourceByIdImpl, updateUserMetaDataImpl, updateThumbnailTimestampImpl))
  }
