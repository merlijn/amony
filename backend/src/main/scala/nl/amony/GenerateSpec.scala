package nl.amony

import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.ListMap
import scala.util.Using

import sttp.apispec.Schema
import sttp.apispec.openapi.circe.yaml.*
import sttp.apispec.openapi.{MediaType, OpenAPI, Operation, PathItem, Response, ResponsesCodeKey}
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import nl.amony.modules.admin.AdminRoutes
import nl.amony.modules.auth.http.AuthRoutes
import nl.amony.modules.config.ConfigRoutes
import nl.amony.modules.resources.http.{CollectionRoutes, ResourceRoutes}
import nl.amony.modules.search.http.SearchRoutes

object GenerateSpec:

  private val endpoints: List[AnyEndpoint] =
    ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthRoutes.endpoints ++ CollectionRoutes.endpoints ++ ConfigRoutes.endpoints

  private val docsInterpreter = OpenAPIDocsInterpreter()

  /**
   * Any endpoint can fail with an unhandled exception, which [[WebServer]] reports as a 500 carrying the
   * shared [[nl.amony.lib.tapir.dsl.error.ErrorBody]]. Document that on every operation so the spec
   * matches what the server can actually return, without widening each endpoint's typed error set.
   */
  private val internalServerError: Response =
    Response(
      description = "Internal server error",
      content     = ListMap("application/json" -> MediaType(schema = Some(Schema.referenceTo("#/components/schemas/", "ErrorBody"))))
    )

  private def withInternalServerError(operation: Operation): Operation =
    if operation.responses.responses.contains(ResponsesCodeKey(500)) then operation
    else operation.addResponse(500, internalServerError)

  private def withInternalServerError(pathItem: PathItem): PathItem =
    pathItem.copy(
      get     = pathItem.get.map(withInternalServerError),
      put     = pathItem.put.map(withInternalServerError),
      post    = pathItem.post.map(withInternalServerError),
      delete  = pathItem.delete.map(withInternalServerError),
      options = pathItem.options.map(withInternalServerError),
      head    = pathItem.head.map(withInternalServerError),
      patch   = pathItem.patch.map(withInternalServerError),
      trace   = pathItem.trace.map(withInternalServerError)
    )

  private def withInternalServerError(docs: OpenAPI): OpenAPI =
    docs.copy(paths = docs.paths.copy(pathItems = docs.paths.pathItems.map { case (path, item) => path -> withInternalServerError(item) }))

  /** The OpenAPI documentation for the API, including the implicit 500 on every operation. */
  private[amony] def documentation: OpenAPI = withInternalServerError(docsInterpreter.toOpenAPI(endpoints, "Amony API", "1.0"))

  def generate(outputPath: Path): Path =
    val absolutePath  = outputPath.toAbsolutePath.normalize()
    val docs: OpenAPI = documentation
    val parent        = absolutePath.getParent
    if parent != null then Files.createDirectories(parent)

    Using.resource(new java.io.FileWriter(absolutePath.toFile))(_.write(docs.toYaml))
    absolutePath

  def main(args: Array[String]): Unit =
    val outputPath  = args.headOption.getOrElse("../frontend/openapi.yaml")
    val writtenPath = generate(Paths.get(outputPath))
    println(s"OpenAPI spec written to: $writtenPath")
