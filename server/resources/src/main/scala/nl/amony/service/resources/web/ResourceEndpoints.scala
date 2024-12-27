package nl.amony.service.resources.web

import cats.effect.IO
import nl.amony.service.auth.tapir.*
import nl.amony.service.auth.{JwtDecoder, Roles}
import nl.amony.service.resources.web.EndpointErrorOut.NotFound
import nl.amony.service.resources.web.JsonCodecs.toDto
import nl.amony.service.resources.web.ResourceWebModel.{ResourceDto, ThumbnailTimestampDto, UserMetaDto}
import nl.amony.service.resources.{Resource, ResourceBucket}
import org.http4s.HttpRoutes
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import sttp.model.StatusCode
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

object ResourceEndpoints:

  val getResourceById: Endpoint[SecurityInput, (String, String), EndpointErrorOut | SecurityError, ResourceDto, Any] = 
    endpoint
      .name("getResourceById")
      .description("Get information about a resource by its id")
      .get.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId"))
      .securityIn(securityInput)
      .errorOut(errorOutput)
      .out(jsonBody[ResourceDto])

  val updateUserMetaData: Endpoint[SecurityInput, (String, String, UserMetaDto), EndpointErrorOut | SecurityError, Unit, Any] =
    endpoint
      .name("updateUserMetaData")
      .description("Get information about a resource by its id")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_user_meta")
      .securityIn(securityInput)
      .in(jsonBody[UserMetaDto])
      .errorOut(errorOutput)
      .out(jsonBody[Unit])
  
  val updateThumbnailTimestamp: Endpoint[SecurityInput, (String, String, ThumbnailTimestampDto), EndpointErrorOut | SecurityError, Unit, Any] = 
    endpoint
      .name("updateThumbnailTimestamp")
      .description("Update the thumbnail timestamp of a resource")
      .post.in("api" / "resources" / path[String]("bucketId") / path[String]("resourceId") / "update_thumbnail_timestamp")
      .securityIn(securityInput)
      .in(jsonBody[ThumbnailTimestampDto])
      .errorOut(errorOutput)
      .out(jsonBody[Unit])
    
  val endpoints = List(getResourceById, updateUserMetaData, updateThumbnailTimestamp)

  def endpointImplementations(buckets: Map[String, ResourceBucket], decoder: JwtDecoder): HttpRoutes[IO] = {

    val authenticator = TapirAuthenticator(decoder)

    def getResource(bucketId: String, resourceId: String): IO[Either[EndpointErrorOut, (ResourceBucket, Resource)]] =
      buckets.get(bucketId) match
        case None         => IO.pure(Left(NotFound))
        case Some(bucket) =>
          bucket.getResource(resourceId).map:
            case None           => Left(NotFound)
            case Some(resource) => Right((bucket, resource))

    val getResourceByIdImpl =
      getResourceById
        .serverSecurityLogic(authenticator.publicEndpoint)
        .serverLogic(_ => (bucketId, resourceId) => 
          getResource(bucketId, resourceId).map(_.map((_, resource) => toDto(resource.info())))
        )

    val updateUserMetaDataImpl =
      updateUserMetaData
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, userMeta) => {
          
          val sanitizedTitle = userMeta.title.map(Jsoup.clean(_, Safelist.basic))
          val sanitizedDescription = userMeta.description.map(Jsoup.clean(_, Safelist.basic))
          val sanitizedTags = userMeta.tags.map(Jsoup.clean(_, Safelist.basic))

          getResource(bucketId, resourceId).flatMap {
            case Left(e)            => IO.pure(Left(e))
            case Right((bucket, _)) => bucket.updateUserMeta(resourceId, sanitizedTitle, sanitizedDescription, sanitizedTags).map(_ => Right(()))
          }
        })
      
    val updateThumbnailTimestampImpl =
      updateThumbnailTimestamp
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogic(_ => (bucketId, resourceId, dto) =>
          getResource(bucketId, resourceId).map(_.map((bucket, _) => bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis)))
        )  

    Http4sServerInterpreter[IO]().toRoutes(
      List(getResourceByIdImpl, updateUserMetaDataImpl, updateThumbnailTimestampImpl)
    )
  }
