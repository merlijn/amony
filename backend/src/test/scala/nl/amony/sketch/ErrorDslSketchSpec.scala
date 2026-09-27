package nl.amony.sketch

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.apispec.openapi.{OpenAPI, ResponsesCodeKey}
import sttp.model.StatusCode
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.json.circe.*
import sttp.tapir.{Schema, *}

import nl.amony.lib.tapir.dsl.error.{BadRequestError, ErrorBody, ErrorResponse, InternalServerError, NotFoundError, SecurityError}
import nl.amony.lib.tapir.dsl.{ServerEndpoints, routes, serverLogic}
import nl.amony.lib.tapir.test.EndpointFixture

/**
 * Exercises the consolidated error DSL from `nl.amony.lib.tapir.dsl.error` using the standard errors:
 *
 *   - `SecurityError` (401/403) is value-based with fixed, opaque messages;
 *   - `NotFoundError` (404) and `BadRequestError` (400) are class-based: one variant per status code,
 *     with the `code`/`message` decided by the server logic at runtime;
 *   - `InternalServerError` (500) is class-based with generic defaults, so its body does not leak internals.
 *
 * The `ErrorResponse[...]` type parameter constrains exactly which errors an endpoint may return, so e.g.
 * `getThing` advertises 404/500 but `createThing` only 400.
 */

// -------------------------------------------------------------------------------------------------
// A few endpoints using the DSL
// -------------------------------------------------------------------------------------------------

case class CreateThingDto(name: String) derives Schema, io.circe.Codec
case class ThingDto(id: String, name: String) derives Schema, io.circe.Codec

object ErrorDslSketchRoutes:

  val errorOutput: EndpointOutput[SecurityError | NotFoundError | InternalServerError] =
    ErrorResponse.of[SecurityError, NotFoundError, InternalServerError].output

  val badRequestErrorOutput: EndpointOutput[SecurityError | BadRequestError] =
    ErrorResponse.of[SecurityError, BadRequestError].output

  val getThing: Endpoint[Unit, String, SecurityError | NotFoundError | InternalServerError, ThingDto, Any] =
    endpoint.name("sketchGetThing").tag("sketch").description("Get a thing by id")
      .get.in("sketch" / "things" / path[String]("id"))
      .errorOut(errorOutput)
      .out(jsonBody[ThingDto])

  val createThing: Endpoint[Unit, CreateThingDto, SecurityError | BadRequestError, ThingDto, Any] =
    endpoint.name("sketchCreateThing").tag("sketch").description("Create a thing")
      .post.in("sketch" / "things")
      .in(jsonBody[CreateThingDto])
      .errorOut(badRequestErrorOutput)
      .out(jsonBody[ThingDto])

  val renameThing: Endpoint[Unit, (String, CreateThingDto), SecurityError | BadRequestError, ThingDto, Any] =
    endpoint.name("sketchRenameThing").tag("sketch").description("Rename a thing, validating the new name")
      .put.in("sketch" / "things" / path[String]("id"))
      .in(jsonBody[CreateThingDto])
      .errorOut(badRequestErrorOutput)
      .out(jsonBody[ThingDto])

  val serverEndpoints: ServerEndpoints[IO] = routes[IO] {
    serverLogic(getThing) { id =>
      val result: Either[SecurityError | NotFoundError | InternalServerError, ThingDto] =
        id match
          case "missing"   => Left(NotFoundError("not_found", "Resource not found"))
          case "forbidden" => Left(SecurityError.Forbidden)
          case "secret"    => Left(SecurityError.Unauthorized)
          case "boom"      => Left(InternalServerError())
          case other       => Right(ThingDto(other, s"Thing $other"))
      IO.pure(result)
    }

    serverLogic(createThing) { dto =>
      val result: Either[SecurityError | BadRequestError, ThingDto] =
        if dto.name.trim.isEmpty then Left(BadRequestError("name_required", "Name must not be blank"))
        else Right(ThingDto("generated", dto.name))
      IO.pure(result)
    }

    serverLogic(renameThing) { (id, dto) =>
      val name                                                      = dto.name.trim
      val result: Either[SecurityError | BadRequestError, ThingDto] =
        if name.isEmpty then Left(BadRequestError("name_required", "Name must not be blank"))
        else if name.length < 3 then Left(BadRequestError("name_too_short", s"Name must be at least 3 characters, got '$name'"))
        else if !name.forall(_.isLetterOrDigit) then
          Left(BadRequestError("invalid_characters", s"Name '$name' may only contain letters and digits"))
        else Right(ThingDto(id, name))
      IO.pure(result)
    }
  }

// -------------------------------------------------------------------------------------------------
// Tests
// -------------------------------------------------------------------------------------------------

class ErrorDslSketchSpec extends AnyWordSpecLike with Matchers:

  import ErrorDslSketchRoutes.*

  private def errorBody(raw: String): ErrorBody =
    decode[ErrorBody](raw) match
      case Right(body) => body
      case Left(error) => fail(s"response body is not an ErrorBody: $error")

  "the consolidated error DSL" should {

    "return the happy path unchanged" in new EndpointFixture(serverEndpoints, getThing) {
      val response = request("id" -> "abc").sendUnsafeSync()

      response.code shouldBe StatusCode.Ok
      decode[ThingDto](response.body.getOrElse(fail("expected a response body"))) shouldBe
        Right(ThingDto("abc", "Thing abc"))
    }

    "return 404 with a not_found body for a missing resource" in new EndpointFixture(serverEndpoints, getThing) {
      val response = request("id" -> "missing").sendUnsafeSync()

      response.code shouldBe StatusCode.NotFound
      errorBody(response.body.merge) shouldBe ErrorBody("not_found", "Resource not found")
    }

    "return 403 with a forbidden body when the caller lacks permission" in new EndpointFixture(serverEndpoints, getThing) {
      val response = request("id" -> "forbidden").sendUnsafeSync()

      response.code shouldBe StatusCode.Forbidden
      errorBody(response.body.merge) shouldBe ErrorBody("forbidden", "You do not have permission to perform this action")
    }

    "return 401 with an unauthorized body when authentication is missing" in new EndpointFixture(serverEndpoints, getThing) {
      val response = request("id" -> "secret").sendUnsafeSync()

      response.code shouldBe StatusCode.Unauthorized
      errorBody(response.body.merge) shouldBe ErrorBody("unauthorized", "Authentication is required")
    }

    "return 500 with a generic body when the server fails" in new EndpointFixture(serverEndpoints, getThing) {
      val response = request("id" -> "boom").sendUnsafeSync()

      response.code shouldBe StatusCode.InternalServerError
      errorBody(response.body.merge) shouldBe ErrorBody("internal_server_error", "Internal server error")
    }

    "let a JSON-body endpoint return a dynamic 400" in new EndpointFixture(serverEndpoints, createThing) {
      val response = request()
        .contentType("application/json")
        .body(CreateThingDto(name = "  ").asJson.noSpaces)
        .sendUnsafeSync()

      response.code shouldBe StatusCode.BadRequest
      errorBody(response.body.merge) shouldBe ErrorBody("name_required", "Name must not be blank")
    }

    "let the server logic pick the code and message for a 400" in new EndpointFixture(serverEndpoints, renameThing) {
      val response = request("id" -> "abc")
        .contentType("application/json")
        .body(CreateThingDto(name = "x").asJson.noSpaces)
        .sendUnsafeSync()

      response.code shouldBe StatusCode.BadRequest
      errorBody(response.body.merge) shouldBe ErrorBody("name_too_short", "Name must be at least 3 characters, got 'x'")
    }

    "use a different dynamic code for a different validation failure" in new EndpointFixture(serverEndpoints, renameThing) {
      val response = request("id" -> "abc")
        .contentType("application/json")
        .body(CreateThingDto(name = "a/b").asJson.noSpaces)
        .sendUnsafeSync()

      response.code shouldBe StatusCode.BadRequest
      errorBody(response.body.merge) shouldBe ErrorBody("invalid_characters", "Name 'a/b' may only contain letters and digits")
    }

    "expose each error variant explicitly in the OpenAPI spec" in {
      val endpoints: List[AnyEndpoint] = List(getThing, createThing, renameThing)
      val docs: OpenAPI                = OpenAPIDocsInterpreter().toOpenAPI(endpoints, "Sketch API", "1.0")

      def codesOf(operation: Option[sttp.apispec.openapi.Operation]) =
        operation.getOrElse(fail("missing operation")).responses.responses.keys.collect { case ResponsesCodeKey(code) => code }.toSet

      val pathItem = docs.paths.pathItems("/sketch/things/{id}")
      codesOf(pathItem.get) should contain allOf (401, 403, 404, 500) // no 400: getThing can't return BadRequestError
      codesOf(pathItem.put) should contain allOf (400, 401, 403)      // 400 via the dynamic BadRequestError

      val schemaNames = docs.components.toList.flatMap(_.schemas.keys)
      schemaNames.exists(_.contains("ErrorBody")) shouldBe true
    }
  }
