package nl.amony.modules.config

import cats.effect.IO
import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.json.circe.jsonBody

import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic}
import nl.amony.modules.resources.api.ThumbnailResolution
import nl.amony.modules.resources.http.required

case class ThumbnailResolutionDto(key: String, height: Int) derives Codec, Schema

/** Client-facing configuration. Public on purpose, so the frontend can load it before (or without) a session. */
case class AppConfigDto(
  @customise(required)
  thumbnailResolutions: List[ThumbnailResolutionDto],
  defaultThumbnailResolution: String
) derives Codec, Schema

object ConfigRoutes extends RoutesModule:

  val getConfig: Endpoint[Unit, Unit, Unit, AppConfigDto, Any] =
    register(endpoint
      .tag("config").name("getConfig").description("Get client configuration")
      .get.in("api" / "config")
      .out(jsonBody[AppConfigDto]))

  def apply(): ServerEndpoints[IO] =
    routes[IO] {
      serverLogic(endpoint = getConfig) { _ =>
        IO.pure(Right(AppConfigDto(
          thumbnailResolutions       = ThumbnailResolution.resolutions.map((key, height) => ThumbnailResolutionDto(key, height)),
          defaultThumbnailResolution = ThumbnailResolution.defaultKey
        )))
      }
    }
