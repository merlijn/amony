package nl.amony

import java.nio.file.{Files, Path, Paths}
import scala.util.Using

import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.yaml.*
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import nl.amony.modules.admin.AdminRoutes
import nl.amony.modules.auth.http.AuthRoutes
import nl.amony.modules.resources.http.{CollectionRoutes, ResourceRoutes}
import nl.amony.modules.search.http.SearchRoutes

object GenerateSpec:

  private val mainEndpoints: List[AnyEndpoint] =
    ResourceRoutes.endpoints ++ SearchRoutes.endpoints ++ AdminRoutes.endpoints ++ AuthRoutes.endpoints ++ CollectionRoutes.endpoints

  // The admin frontend only needs the auth flow (to log in and refresh its own tokens) and the admin endpoints.
  private val adminEndpoints: List[AnyEndpoint] =
    AdminRoutes.endpoints ++ AuthRoutes.endpoints

  private val docsInterpreter = OpenAPIDocsInterpreter()

  private def write(endpoints: List[AnyEndpoint], title: String, outputPath: Path): Path =
    val absolutePath  = outputPath.toAbsolutePath.normalize()
    val docs: OpenAPI = docsInterpreter.toOpenAPI(endpoints, title, "1.0")
    val parent        = absolutePath.getParent
    if parent != null then Files.createDirectories(parent)

    Using.resource(new java.io.FileWriter(absolutePath.toFile))(_.write(docs.toYaml))
    absolutePath

  def generate(outputPath: Path): Path =
    write(mainEndpoints, "Amony API", outputPath)

  def generateAdmin(outputPath: Path): Path =
    write(adminEndpoints, "Amony Admin API", outputPath)

  def main(args: Array[String]): Unit =
    val mainOutputPath  = args.headOption.getOrElse("../frontend/openapi.yaml")
    val adminOutputPath = args.lift(1).getOrElse("../admin-frontend/openapi.yaml")

    val writtenMain  = generate(Paths.get(mainOutputPath))
    val writtenAdmin = generateAdmin(Paths.get(adminOutputPath))

    println(s"OpenAPI spec written to: $writtenMain")
    println(s"Admin OpenAPI spec written to: $writtenAdmin")
