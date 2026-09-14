package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.Http4sServerOptions

import nl.amony.lib.tapir.dsl.{RoutesModule, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{Collection, CollectionId, ResourceId}
import nl.amony.modules.resources.dal.CollectionsDal

object CollectionRoutes extends RoutesModule:

  val getCollections: Endpoint[SecurityInput, Unit, ApiError | SecurityError, List[CollectionDto], Any] =
    register(endpoint
      .name("getCollections").tag("collections").description("Get all collections for the current user")
      .get.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[CollectionDto]]))

  val createCollection: Endpoint[SecurityInput, CreateCollectionDto, ApiError | SecurityError, CollectionDto, Any] =
    register(endpoint
      .name("createCollection").tag("collections").description("Create a new collection")
      .post.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .in(jsonBody[CreateCollectionDto])
      .out(apiNoCacheHeaders).out(jsonBody[CollectionDto]))

  val addResourceToCollection: Endpoint[SecurityInput, (CollectionId, String, ResourceId), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("addResourceToCollection").tag("collections").description("Add a resource to a collection")
      .post.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput))

  val removeResourceFromCollection: Endpoint[SecurityInput, (CollectionId, String, ResourceId), ApiError | SecurityError, Unit, Any] =
    register(endpoint
      .name("removeResourceFromCollection").tag("collections").description("Remove a resource from a collection")
      .delete.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput))

  val getResourcesInCollection: Endpoint[SecurityInput, CollectionId, ApiError | SecurityError, List[ResourceDto], Any] =
    register(endpoint
      .name("getResourcesInCollection").tag("collections").description("Get all resources in a collection")
      .get.in("api" / "collections" / path[CollectionId]("collectionId") / "resources")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[ResourceDto]]))

  def apply(collectionsDal: CollectionsDal)(
    using serverOptions: Http4sServerOptions[IO],
    apiSecurity: ApiSecurity
  ): HttpRoutes[IO] = {

    routes[IO](serverOptions) {

      serverLogic(endpoint = getCollections, requiredPermission = Permission.ManageCollections) { auth => _ =>
        collectionsDal.getCollectionsForUser(auth.userId).map(_.map(toDto)).map(Right(_))
      }

      serverLogicT(endpoint = createCollection, requiredPermission = Permission.ManageCollections) { auth => dto =>
        for
          sanitizedName        <- sanitize(dto.name, 128, _ => true)
          sanitizedDescription <- sanitizeOpt(dto.description, 1280, _ => true)
          sanitizedTags        <- sanitizeTags(dto.tags)
          collection            = Collection(
                                    id          = CollectionId(java.util.UUID.randomUUID()),
                                    parentId    = dto.parentId,
                                    userId      = auth.userId,
                                    name        = sanitizedName,
                                    description = sanitizedDescription,
                                    tags        = sanitizedTags.toSet
                                  )
          _                    <- EitherT.right[ApiError](collectionsDal.insertCollection(collection))
        yield toDto(collection)
      }

      serverLogic(endpoint = addResourceToCollection, requiredPermission = Permission.ManageCollections) {
        _ => (collectionId, bucketId, resourceId) =>
          collectionsDal.addResourceToCollection(collectionId, bucketId, resourceId).map(Right(_))
      }

      serverLogic(endpoint = removeResourceFromCollection, requiredPermission = Permission.ManageCollections) {
        _ => (collectionId, bucketId, resourceId) =>
          collectionsDal.removeResourceFromCollection(collectionId, bucketId, resourceId).map(Right(_))
      }

      serverLogic(endpoint = getResourcesInCollection, requiredPermission = Permission.ManageCollections) {
        token => collectionId =>
          collectionsDal.getCollectionById(collectionId).flatMap {
            case Some(collection) if collection.userId == token.userId =>
              collectionsDal.getResourcesInCollection(collectionId).map(_.map(toDto)).map(Right(_))
            case _                                                     =>
              IO.pure(Left(ApiError.NotFound))
          }
      }
    }
  }
