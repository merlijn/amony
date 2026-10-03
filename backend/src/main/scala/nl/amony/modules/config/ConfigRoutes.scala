package nl.amony.modules.config

import cats.effect.IO
import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.json.circe.jsonBody

import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic}
import nl.amony.lib.tapir.required
import nl.amony.modules.resources.ResolutionPickingStrategy
import nl.amony.modules.resources.api.{ThumbnailDimension, ThumbnailFormats, ThumbnailResolutions}

/** A concrete thumbnail choice: a dimension ("width" or "height") plus a size in pixels. */
case class ThumbnailResolutionDto(dimension: String, pixels: Int) derives Codec, Schema

/** Client-facing configuration. Public on purpose, so the frontend can load it before (or without) a session. */
case class AppConfigDto(
  @customise(required)
  thumbnailSizes: List[Int],
  @customise(required)
  supportedFormats: List[String],
  defaultThumbnailResolution: ThumbnailResolutionDto,
  resolutionPickingStrategy: String
) derives Codec, Schema

object ConfigRoutes extends RoutesModule:

  val getConfig: Endpoint[Unit, Unit, Unit, AppConfigDto, Any] =
    register(endpoint
      .tag("config").name("getConfig").description("Get client configuration")
      .get.in("api" / "config")
      .out(jsonBody[AppConfigDto]))

  def apply(resolutions: ThumbnailResolutions, formats: ThumbnailFormats, pickingStrategy: ResolutionPickingStrategy): ServerEndpoints[IO] =
    routes[IO] {
      serverLogic(endpoint = getConfig) { _ =>
        IO.pure(Right(AppConfigDto(
          thumbnailSizes             = resolutions.sizes,
          supportedFormats           = formats.formats.map(_.configName),
          // Thumbnails are displayed in fixed-aspect boxes, so width is the operative dimension by default.
          defaultThumbnailResolution = ThumbnailResolutionDto(ThumbnailDimension.Width.name, resolutions.default),
          resolutionPickingStrategy  = pickingStrategy.configName
        )))
      }
    }
