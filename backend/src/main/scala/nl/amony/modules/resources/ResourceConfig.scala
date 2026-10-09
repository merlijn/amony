package nl.amony.modules.resources

import pureconfig.*
import pureconfig.error.CannotConvert

import nl.amony.modules.resources.api.{ImageFormat, ResourceBucketConfig}

enum ResolutionPickingStrategy(val configName: String):
  case RoundUp      extends ResolutionPickingStrategy("round-up")
  case RoundDown    extends ResolutionPickingStrategy("round-down")
  case RoundNearest extends ResolutionPickingStrategy("round-nearest")

object ResolutionPickingStrategy:
  given ConfigReader[ResolutionPickingStrategy] =
    ConfigReader[String].emap { name =>
      values.find(_.configName == name).toRight(
        CannotConvert(name, "ResolutionPickingStrategy", s"Expected one of: ${values.map(_.configName).mkString(", ")}")
      )
    }

given ConfigReader[ImageFormat] =
  ConfigReader[String].emap { name =>
    ImageFormat.fromName(name).toRight(
      CannotConvert(name, "ImageFormat", s"Expected one of: ${ImageFormat.values.map(_.configName).mkString(", ")}")
    )
  }

/**
 * Pureconfig only ships a `Map[String, A]` reader, so this adapts the `format-options` map to
 * `ImageFormat` keys, failing fast on an unknown format name.
 */
given ConfigReader[Map[ImageFormat, List[String]]] =
  ConfigReader[Map[String, List[String]]].emap { raw =>
    raw.foldLeft[Either[CannotConvert, Map[ImageFormat, List[String]]]](Right(Map.empty)) {
      case (result, (name, args)) =>
        for
          options <- result
          format  <- ImageFormat.fromName(name).toRight(
                       CannotConvert(name, "ImageFormat", s"Expected one of: ${ImageFormat.values.map(_.configName).mkString(", ")}")
                     )
        yield options.updated(format, args)
    }
  }

case class ThumbnailConfig(
  allowedResolutions: List[Int],
  defaultResolution: Int,
  supportedImageFormats: List[ImageFormat],
  supportedVideoFormats: List[String]           = List("mp4"),
  resolutionStepDown: Int,
  resolutionPickingStrategy: ResolutionPickingStrategy,
  formatOptions: Map[ImageFormat, List[String]] = Map.empty
) derives ConfigReader

case class ResourceConfig(previews: ThumbnailConfig, defaultBucket: ResourceBucketConfig, parallelFactor: Int = 4) derives ConfigReader

object ResourceConfig {

  case class TranscodeSettings(format: String, scaleHeight: Int, crf: Int) derives ConfigReader
}
