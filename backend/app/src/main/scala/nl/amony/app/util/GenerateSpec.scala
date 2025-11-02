package nl.amony.app.util

import nl.amony.app.routes.AdminRoutes
import nl.amony.service.auth.AuthRoutes
import nl.amony.service.resources.web.ResourceRoutes
import nl.amony.service.search.SearchRoutes
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import java.nio.file.{Files, Path}
import scala.util.Using

object GenerateSpec:

  private val endpoints: List[AnyEndpoint] =
    ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthRoutes.endpoints

  private val docsInterpreter = OpenAPIDocsInterpreter()

  def generate(outputPath: Path): Path =
    val absolutePath = outputPath.toAbsolutePath.normalize()
    val docs: OpenAPI = docsInterpreter.toOpenAPI(endpoints, "Amony API", "1.0")
    val parent = absolutePath.getParent
    if parent != null then Files.createDirectories(parent)

    Using.resource(new java.io.FileWriter(absolutePath.toFile))(_.write(docs.toYaml))
    absolutePath
