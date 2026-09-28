package nl.amony.modules.config

import cats.effect.IO
import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.json.circe.jsonBody

import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic}
import nl.amony.modules.resources.api.{ThumbnailDimension, ThumbnailResolutions}
import nl.amony.modules.resources.http.required

/** A configured resolution key and the pixel size it pins for the chosen dimension. */
case class ThumbnailSizeDto(key: String, pixels: Int) derives Codec, Schema

/** A concrete thumbnail choice: a dimension ("width" or "height") plus a resolution key. */
case class ThumbnailResolutionDto(dimension: String, key: String) derives Codec, Schema

/** Client-facing configuration. Public on purpose, so the frontend can load it before (or without) a session. */
case class AppConfigDto(
  @customise(required)
  thumbnailSizes: List[ThumbnailSizeDto],
  @customise(required)
  thumbnailDimensions: List[String],
  defaultThumbnailResolution: ThumbnailResolutionDto
) derives Codec, Schema

object ConfigRoutes extends RoutesModule:

  val getConfig: Endpoint[Unit, Unit, Unit, AppConfigDto, Any] =
    register(endpoint
      .tag("config").name("getConfig").description("Get client configuration")
      .get.in("api" / "config")
      .out(jsonBody[AppConfigDto]))

  def apply(resolutions: ThumbnailResolutions): ServerEndpoints[IO] =
    routes[IO] {
      serverLogic(endpoint = getConfig) { _ =>
        IO.pure(Right(AppConfigDto(
          thumbnailSizes  = resolutions.sizes.map(size => ThumbnailSizeDto(size.key, size.pixels)),
          thumbnailDimensions = resolutions.dimensions.map(_.name),
          // Thumbnails are displayed in fixed-aspect boxes, so width is the operative dimension by default.
          defaultThumbnailResolution = ThumbnailResolutionDto(ThumbnailDimension.Width.name, resolutions.default.key)
        )))
      }
    }
