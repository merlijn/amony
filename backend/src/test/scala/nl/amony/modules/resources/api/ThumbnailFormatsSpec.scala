package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

class ThumbnailFormatsSpec extends AnyWordSpecLike {

  private val formats = ThumbnailFormats(List(ImageFormat.Avif, ImageFormat.Jxl, ImageFormat.Webp, ImageFormat.Jpeg))

  "ThumbnailFormats" should {

    "keep the configured preference order" in {
      assert(formats.formats == List(ImageFormat.Avif, ImageFormat.Jxl, ImageFormat.Webp, ImageFormat.Jpeg))
      assert(formats.default == ImageFormat.Avif)
    }

    "resolve a known format by name" in {
      assert(formats.resolve("webp") == ImageFormat.Webp)
      assert(formats.resolve("jxl") == ImageFormat.Jxl)
    }

    "fall back to the most preferred format for unknown or unsupported names" in {
      assert(formats.resolve("bmp") == ImageFormat.Avif)
      assert(formats.resolve("") == ImageFormat.Avif)
    }

    "reject an empty or duplicate configuration" in {
      assertThrows[IllegalArgumentException](ThumbnailFormats(Nil))
      assertThrows[IllegalArgumentException](ThumbnailFormats(List(ImageFormat.Webp, ImageFormat.Webp)))
    }
  }

  "ImageFormat" should {
    "expose the mime type and extension per format" in {
      assert(ImageFormat.Avif.mimeType == "image/avif" && ImageFormat.Avif.extension == "avif")
      assert(ImageFormat.Jpeg.mimeType == "image/jpeg" && ImageFormat.Jpeg.extension == "jpeg")
    }

    "hardcode a fast AV1 encoder for AVIF (see #51)" in {
      assert(ImageFormat.Avif.ffmpegEncoderArgs.contains("libsvtav1"))
      assert(ImageFormat.Webp.ffmpegEncoderArgs.isEmpty)
    }

    "only declare a minimum side for AVIF (SVT-AV1 rejects sides below 64px)" in {
      assert(ImageFormat.Avif.minimumDimension.contains(64))
      assert(ImageFormat.Jxl.minimumDimension.isEmpty && ImageFormat.Webp.minimumDimension.isEmpty && ImageFormat.Jpeg.minimumDimension.isEmpty)
    }
  }
}
