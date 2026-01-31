package nl.amony

import java.nio.file.{Files, Path}
import scala.util.Using

import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import nl.amony.modules.admin.AdminRoutes
import nl.amony.modules.auth.http.{AuthEndpointDefs, AuthEndpointServerLogic}
import nl.amony.modules.resources.http.ResourceRoutes
import nl.amony.modules.search.http.SearchRoutes

object GenerateSpec:

  private val endpoints: List[AnyEndpoint] = ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthEndpointDefs.endpoints

  private val docsInterpreter = OpenAPIDocsInterpreter()

  def generate(outputPath: Path): Path =
    val absolutePath  = outputPath.toAbsolutePath.normalize()
    val docs: OpenAPI = docsInterpreter.toOpenAPI(endpoints, "Amony API", "1.0")
    val parent        = absolutePath.getParent
    if parent != null then Files.createDirectories(parent)

    Using.resource(new java.io.FileWriter(absolutePath.toFile))(_.write(docs.toYaml))
    absolutePath
