package nl.amony.lib.ffmpeg

import java.nio.file.Path

import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpecLike
import org.typelevel.otel4s.metrics.MeterProvider
import scribe.Logging

import nl.amony.lib.process.magick.ImageMagick

class ImageMagickSpec extends AnyFlatSpecLike with Logging {

  ignore should "get the meta data of an image" in {

    val path = Path.of("/Users/merlijn/dev/stable-diffusion-webui/outputs/txt2img-images/2023-03-19/00006-3780544666.png")

    val imageMagick = new ImageMagick(MeterProvider.noop[IO])

    val metas = imageMagick.getImageMeta(path)

//     logger.info(metas.unsafeRunSync().mkString(","))
  }
}
