package nl.amony.lib.ffmpeg

import java.nio.file.Path

import cats.effect.IO
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.lib.process.magick.ImageMagick

class ImageMagickSpec extends AnyWordSpecLike with Logging {

  "ImageMagick" should {

    "get the meta data of an image" ignore {
      val path = Path.of("/Users/merlijn/dev/stable-diffusion-webui/outputs/txt2img-images/2023-03-19/00006-3780544666.png")

      val imageMagick = new ImageMagick(using Meter.noop[IO], Tracer.noop[IO])

      imageMagick.getImageMeta(path)

      // logger.info(metas.unsafeRunSync().mkString(","))
    }
  }
}
