package nl.amony.app.util

import nl.amony.app.routes.AdminRoutes
import nl.amony.search.SearchRoutes
import nl.amony.service.auth.AuthRoutes
import nl.amony.service.resources.web.ResourceRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.apispec.openapi.OpenAPI
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import java.io.File
import java.nio.file.Path
import scala.util.Using

@main
def generateSpec(): Unit = {

  val endpoints = ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthRoutes.endpoints

  val outputPath = "../../frontend/openapi.yaml"

  val docs: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(endpoints, "Amony API", "1.0")
  
  val path = Path.of(outputPath).toAbsolutePath.normalize()

  println(s"writing open api spec to: $path")

  Using(new java.io.FileWriter(path.toFile)) { writer =>

    writer.write(docs.toYaml)
  }
}

