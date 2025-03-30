package nl.amony.lib.ffmpeg

import cats.effect.unsafe.implicits.global
import nl.amony.lib.magick.ImageMagick
import org.scalatest.flatspec.AnyFlatSpecLike
import scribe.Logging

import java.nio.file.Path

class ImageMagickSpec extends AnyFlatSpecLike with Logging {

   ignore should "get the meta data of an image" in {

     val path = Path.of("/Users/merlijn/dev/stable-diffusion-webui/outputs/txt2img-images/2023-03-19/00006-3780544666.png")

     val metas = ImageMagick.getImageMeta(path)

//     logger.info(metas.unsafeRunSync().mkString(","))
   }
}
