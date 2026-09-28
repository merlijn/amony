package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import sttp.tapir.*
import sttp.tapir.json.circe.*

import nl.amony.lib.tapir.dsl.error.{BadRequestError, NotFoundError, SecurityError}
import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic, serverLogicT}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{BucketId, Collection, CollectionId, ResourceId}
import nl.amony.modules.resources.dal.CollectionsDal

object CollectionRoutes extends RoutesModule:

  val getCollections: Endpoint[SecurityInput, Unit, SecurityError | NotFoundError | BadRequestError, List[CollectionDto], Any] =
    register(endpoint
      .name("getCollections").tag("collections").description("Get all collections for the current user")
      .get.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[CollectionDto]]))

  val createCollection: Endpoint[SecurityInput, CreateCollectionDto, SecurityError | NotFoundError | BadRequestError, CollectionDto, Any] =
    register(endpoint
      .name("createCollection").tag("collections").description("Create a new collection")
      .post.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .in(jsonBody[CreateCollectionDto])
      .out(apiNoCacheHeaders).out(jsonBody[CollectionDto]))

  val addResourceToCollection
    : Endpoint[SecurityInput, (CollectionId, BucketId, ResourceId), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("addResourceToCollection").tag("collections").description("Add a resource to a collection")
      .post.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[BucketId]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput))

  val removeResourceFromCollection
    : Endpoint[SecurityInput, (CollectionId, BucketId, ResourceId), SecurityError | NotFoundError | BadRequestError, Unit, Any] =
    register(endpoint
      .name("removeResourceFromCollection").tag("collections").description("Remove a resource from a collection")
      .delete.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[BucketId]("bucketId") / path[ResourceId](
        "resourceId"
      ))
      .securityIn(securityInput).errorOut(errorOutput))

  val getResourcesInCollection: Endpoint[SecurityInput, CollectionId, SecurityError | NotFoundError | BadRequestError, List[ResourceDto], Any] =
    register(endpoint
      .name("getResourcesInCollection").tag("collections").description("Get all resources in a collection")
      .get.in("api" / "collections" / path[CollectionId]("collectionId") / "resources")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[ResourceDto]]))

  def apply(collectionsDal: CollectionsDal)(
    using apiSecurity: ApiSecurity
  ): ServerEndpoints[IO] = {

    def isBucketHidden(auth: AuthToken, bucketId: BucketId): Boolean =
      apiSecurity.userAccess(auth).hiddenBuckets.contains(bucketId)

    routes[IO] {

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
                                    parentId    = dto.parentId.map(CollectionId(_)),
                                    userId      = auth.userId,
                                    name        = sanitizedName,
                                    description = sanitizedDescription,
                                    tags        = sanitizedTags.toSet
                                  )
          _                    <- EitherT.right[BadRequestError | NotFoundError](collectionsDal.insertCollection(collection))
        yield toDto(collection)
      }

      serverLogicT(endpoint = addResourceToCollection, requiredPermission = Permission.ManageCollections) {
        auth => (collectionId, bucketId, resourceId) =>
          for
            _ <- EitherT.cond[IO](!isBucketHidden(auth, bucketId), (), NotFoundError("not_found", "Resource not found"))
            _ <- EitherT.right[BadRequestError | NotFoundError](collectionsDal.addResourceToCollection(collectionId, bucketId, resourceId))
          yield ()
      }

      serverLogicT(endpoint = removeResourceFromCollection, requiredPermission = Permission.ManageCollections) {
        auth => (collectionId, bucketId, resourceId) =>
          for
            _ <- EitherT.cond[IO](!isBucketHidden(auth, bucketId), (), NotFoundError("not_found", "Resource not found"))
            _ <- EitherT.right[BadRequestError | NotFoundError](collectionsDal.removeResourceFromCollection(collectionId, bucketId, resourceId))
          yield ()
      }

      serverLogic(endpoint = getResourcesInCollection, requiredPermission = Permission.ManageCollections) {
        token => collectionId =>
          collectionsDal.getCollectionById(collectionId).flatMap {
            case Some(collection) if collection.userId == token.userId =>
              val hiddenBuckets = apiSecurity.userAccess(token).hiddenBuckets
              collectionsDal.getResourcesInCollection(collectionId)
                .map(_.filterNot(resource => hiddenBuckets.contains(resource.bucketId)).map(toDto)).map(Right(_))
            case _                                                     =>
              IO.pure(Left(NotFoundError("not_found", "Collection not found")))
          }
      }
    }
  }
