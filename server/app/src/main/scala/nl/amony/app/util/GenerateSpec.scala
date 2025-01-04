package nl.amony.app.util

import nl.amony.app.routes.AdminRoutes
import nl.amony.search.SearchRoutes
import nl.amony.service.auth.AuthRoutes
import nl.amony.service.resources.web.ResourceRoutes
import sttp.apispec.openapi.circe.yaml.*
import sttp.apispec.openapi.OpenAPI
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import scala.util.Using

@main
def generateSpec(): Unit = {

  val endpoints = ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthRoutes.endpoints

  val outputPath = "../web-client/openapi.yaml"

  val docs: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(endpoints, "Amony API", "1.0")

  Using(new java.io.FileWriter(outputPath)) { writer =>
    writer.write(docs.toYaml)
  }
}

