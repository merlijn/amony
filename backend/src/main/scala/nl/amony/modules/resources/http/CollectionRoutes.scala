package nl.amony.modules.resources.http

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{Collection, CollectionId, ResourceId}
import nl.amony.modules.resources.dal.CollectionsDal

object CollectionRoutes:

  val getCollections: Endpoint[SecurityInput, Unit, ApiError | SecurityError, List[CollectionDto], Any] =
    endpoint
      .name("getCollections").tag("collections").description("Get all collections for the current user")
      .get.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[CollectionDto]])

  val createCollection: Endpoint[SecurityInput, CreateCollectionDto, ApiError | SecurityError, CollectionDto, Any] =
    endpoint
      .name("createCollection").tag("collections").description("Create a new collection")
      .post.in("api" / "collections")
      .securityIn(securityInput).errorOut(errorOutput)
      .in(jsonBody[CreateCollectionDto])
      .out(apiNoCacheHeaders).out(jsonBody[CollectionDto])

  val addResourceToCollection: Endpoint[SecurityInput, (CollectionId, String, ResourceId), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("addResourceToCollection").tag("collections").description("Add a resource to a collection")
      .post.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)

  val removeResourceFromCollection: Endpoint[SecurityInput, (CollectionId, String, ResourceId), ApiError | SecurityError, Unit, Any] =
    endpoint
      .name("removeResourceFromCollection").tag("collections").description("Remove a resource from a collection")
      .delete.in("api" / "collections" / path[CollectionId]("collectionId") / "resources" / path[String]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput).errorOut(errorOutput)

  val getResourcesInCollection: Endpoint[SecurityInput, CollectionId, ApiError | SecurityError, List[ResourceDto], Any] =
    endpoint
      .name("getResourcesInCollection").tag("collections").description("Get all resources in a collection")
      .get.in("api" / "collections" / path[CollectionId]("collectionId") / "resources")
      .securityIn(securityInput).errorOut(errorOutput)
      .out(apiNoCacheHeaders).out(jsonBody[List[ResourceDto]])

  val endpoints = List(getCollections, createCollection, addResourceToCollection, removeResourceFromCollection, getResourcesInCollection)

  def apply(collectionsDal: CollectionsDal, apiSecurity: ApiSecurity)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    def sanitizeOpt(input: Option[String], maxLength: Int, characterAllowFn: Char => Boolean): EitherT[IO, ApiError, Option[String]] =
      input.map(sanitize(_, maxLength, characterAllowFn).map(Some(_))).getOrElse(EitherT.rightT[IO, ApiError](None))

    def sanitizeTags(tags: List[String]): EitherT[IO, ApiError, List[String]] = tags.map(sanitize(_, 64, _.isLetterOrDigit)).sequence

    val getCollectionsImpl = getCollections.serverSecurityLogicPure(s => apiSecurity.requireSession(s))
      .serverLogic { token => _ =>
        collectionsDal.getCollectionsForUser(token.userId).map(_.map(toDto)).map(Right(_))
      }

    val createCollectionImpl = createCollection.serverSecurityLogicPure(s => apiSecurity.requireSession(s))
      .serverLogic { token => dto =>
        (for
          sanitizedName        <- sanitize(dto.name, 128, _ => true)
          sanitizedDescription <- sanitizeOpt(dto.description, 1280, _ => true)
          sanitizedTags        <- sanitizeTags(List.empty)
          collection            = Collection(
                                    id          = CollectionId(java.util.UUID.randomUUID()),
                                    parentId    = dto.parentId,
                                    userId      = token.userId,
                                    name        = sanitizedName,
                                    description = sanitizedDescription,
                                    tags        = sanitizedTags.toSet
                                  )
          _                    <- EitherT.right[ApiError](collectionsDal.insertCollection(collection))
        yield toDto(collection)).value
      }

    val addResourceToCollectionImpl = addResourceToCollection.serverSecurityLogicPure(s => apiSecurity.requireSession(s))
      .serverLogic { _ => (collectionId, bucketId, resourceId) =>
        collectionsDal.addResourceToCollection(collectionId, bucketId, resourceId).map(Right(_))
      }

    val removeResourceFromCollectionImpl = removeResourceFromCollection.serverSecurityLogicPure(s => apiSecurity.requireSession(s))
      .serverLogic { _ => (collectionId, bucketId, resourceId) =>
        collectionsDal.removeResourceFromCollection(collectionId, bucketId, resourceId).map(Right(_))
      }

    val getResourcesInCollectionImpl = getResourcesInCollection.serverSecurityLogicPure(s => apiSecurity.requireSession(s))
      .serverLogic { token => collectionId =>
        collectionsDal.getCollectionById(collectionId).flatMap {
          case Some(collection) if collection.userId == token.userId =>
            collectionsDal.getResourcesInCollection(collectionId).map(_.map(toDto)).map(Right(_))
          case _                                                     =>
            IO.pure(Left(ApiError.NotFound))
        }
      }

    Http4sServerInterpreter[IO](serverOptions)
      .toRoutes(List(
        getCollectionsImpl,
        createCollectionImpl,
        addResourceToCollectionImpl,
        removeResourceFromCollectionImpl,
        getResourcesInCollectionImpl
      ))
  }
