package nl.amony.service.resources.web

import cats.data.EitherT
import cats.effect.IO
import nl.amony.service.auth.tapir.*
import nl.amony.service.auth.{Authenticator, JwtDecoder, Roles, SecurityError}
import nl.amony.service.resources.web.EndpointErrorOut.NotFound
import nl.amony.service.resources.web.dto.*
import nl.amony.service.resources.{Resource, ResourceBucket}
import org.http4s.HttpRoutes
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

def oneOfList[T](variants: List[OneOfVariant[_ <: T]]) = EndpointOutput.OneOf[T, T](variants, Mapping.id)

enum EndpointErrorOut:
  case NotFound

val apiErrorOutputs = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(EndpointErrorOut.NotFound),
)

val errorOutput: EndpointOutput[EndpointErrorOut | SecurityError] = oneOfList(securityErrors ++ apiErrorOutputs)

object ResourceRoutes:

  private val apiCacheHeaders: EndpointOutput[Unit] = List(
    header(HeaderNames.CacheControl, "no-cache, no-store, must-revalidate"),
    header(HeaderNames.Pragma, "no-cache"),
    header(HeaderNames.Expires, "0")
  ).reduce(_ and _)

  val getResourceById: Endpoint[SecurityInput, (String, String), EndpointErrorOut | SecurityError, ResourceDto, Any] =
    endpoint
      .name("getResourceById")
      .tag("resources")
      .description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId"))
      .securityIn(securityInput)
      .errorOut(errorOutput)
      .out(apiCacheHeaders)
      .out(jsonBody[ResourceDto])

  val updateUserMetaData: Endpoint[SecurityInput, (String, String, UserMetaDto), EndpointErrorOut | SecurityError, Unit, Any] =
    endpoint
      .name("updateUserMetaData")
      .tag("resources")
      .description("Update the user metadata of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto])
      .errorOut(errorOutput)
  
  val updateThumbnailTimestamp: Endpoint[SecurityInput, (String, String, ThumbnailTimestampDto), EndpointErrorOut | SecurityError, Unit, Any] = 
    endpoint
      .name("updateThumbnailTimestamp")
      .tag("resources")
      .description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto])
      .errorOut(errorOutput)
    
  val endpoints = List(getResourceById, updateUserMetaData, updateThumbnailTimestamp)

  def endpointImplementations(buckets: Map[String, ResourceBucket], decoder: JwtDecoder): HttpRoutes[IO] = {

    val authenticator = Authenticator(decoder)

    def getResource(bucketId: String, resourceId: String): EitherT[IO, EndpointErrorOut, (ResourceBucket, Resource)] = 
      for {
        bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), NotFound)
        resource <- EitherT.fromOptionF(bucket.getResource(resourceId), NotFound)
      } yield bucket -> resource

    val getResourceByIdImpl =
      getResourceById
        .serverSecurityLogic(authenticator.publicEndpoint)
        .serverLogic(_ => (bucketId, resourceId) =>
          getResource(bucketId, resourceId).map((_, resource) => toDto(resource.info())).value
        )

    val updateUserMetaDataImpl =
      updateUserMetaData
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, userMeta) => {
          
          val sanitizedTitle       = userMeta.title.map(Jsoup.clean(_, Safelist.basic))
          val sanitizedDescription = userMeta.description.map(Jsoup.clean(_, Safelist.basic))
          val sanitizedTags        = userMeta.tags.map(Jsoup.clean(_, Safelist.basic))

          getResource(bucketId, resourceId).flatMap {
            (bucket, _) => EitherT.right(bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags)) 
          }.value
        })
      
    val updateThumbnailTimestampImpl =
      updateThumbnailTimestamp
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, dto) =>
          getResource(bucketId, resourceId).flatMap {
            (bucket, _) => EitherT.right(bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis))
          }.value
        )

    Http4sServerInterpreter[IO]().toRoutes(
      List(getResourceByIdImpl, updateUserMetaDataImpl, updateThumbnailTimestampImpl)
    )
  }
