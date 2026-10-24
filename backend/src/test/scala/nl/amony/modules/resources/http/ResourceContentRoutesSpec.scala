package nl.amony.modules.resources.http

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*

class ResourceContentRoutesSpec extends AnyWordSpecLike {

  private val resolutions      = ThumbnailResolutions(List(352, 4320), 352, stepDown = 0)
  private val smallResolutions = ThumbnailResolutions(List(96, 192, 384, 768, 1536), 384, stepDown = 0)
  private val formats          = ThumbnailFormats(List(ImageFormat.Avif, ImageFormat.Webp, ImageFormat.Jpeg))

  private def videoInfo(width: Int, height: Int, durationInMillis: Int = 10000, thumbnailTimestamp: Int = 1000) = ResourceInfo(
    bucketId           = BucketId("test"),
    resourceId         = ResourceId("resource"),
    userId             = UserId("user"),
    path               = "video.mp4",
    size               = 1L,
    contentMeta        = ContentMeta.indexed(Some(VideoProperties(width, height, 30f, durationInMillis))),
    thumbnailTimestamp = Some(thumbnailTimestamp)
  )

  private def imageInfo(width: Int, height: Int) = ResourceInfo(
    bucketId    = BucketId("test"),
    resourceId  = ResourceId("resource"),
    userId      = UserId("user"),
    path        = "image.jpg",
    size        = 1L,
    contentMeta = ContentMeta.indexed(Some(ImageProperties(width, height)))
  )

  private def thumbOp(
    ts: Long,
    dim: String,
    resKey: String,
    format: String,
    info: ResourceInfo,
    res: ThumbnailResolutions = resolutions
  ) =
    ResourceContentRoutes.patterns
      .thumbnailOperation(ResourceOperations.contentVersion(info), ts, dim, resKey, format, res, formats, info)

  private def clipOp(start: Long, end: Long, dim: String, resKey: String, info: ResourceInfo) =
    ResourceContentRoutes.patterns
      .clipOperation(ResourceOperations.contentVersion(info), start, end, dim, resKey, resolutions, info)

  "ResourceContentRoutes" when {

    "building a thumbnail operation" should {

      "cap a width request at the source width instead of upscaling" in {
        val op = thumbOp(1000, "w", "4320", "webp", videoInfo(1920, 1080))
        assert(op == Some(VideoThumbnail(width = Some(1920), height = None, timestamp = 1000, format = ImageFormat.Webp)))
      }

      "cap a height request at the source height instead of upscaling" in {
        val op = thumbOp(1000, "h", "4320", "webp", videoInfo(1920, 1080))
        assert(op == Some(VideoThumbnail(width = None, height = Some(1080), timestamp = 1000, format = ImageFormat.Webp)))
      }

      "round video dimensions down to an even number" in {
        val op = thumbOp(1000, "w", "4320", "webp", videoInfo(1921, 1081))
        assert(op == Some(VideoThumbnail(width = Some(1920), height = None, timestamp = 1000, format = ImageFormat.Webp)))
      }

      "not cap a request smaller than the source" in {
        val op = thumbOp(1000, "w", "352", "webp", videoInfo(1920, 1080))
        assert(op == Some(VideoThumbnail(width = Some(352), height = None, timestamp = 1000, format = ImageFormat.Webp)))
      }

      "serve a lower rung when resolution-step-down is configured" in {
        val stepped = ThumbnailResolutions(List(352, 4320), 352, stepDown = 1)
        val op      = thumbOp(1000, "w", "4320", "webp", videoInfo(1920, 1080), stepped)
        assert(op == Some(VideoThumbnail(width = Some(352), height = None, timestamp = 1000, format = ImageFormat.Webp)))
      }

      "use the requested image format" in {
        val op = thumbOp(0, "w", "352", "avif", imageInfo(1600, 1063))
        assert(op == Some(ImageThumbnail(width = Some(352), height = None, format = ImageFormat.Avif)))
      }

      "fall back to the most preferred format for an unsupported one" in {
        val op = thumbOp(0, "w", "352", "bmp", imageInfo(1600, 1063))
        assert(op == Some(ImageThumbnail(width = Some(352), height = None, format = ImageFormat.Avif)))
      }

      "cap image thumbnails without rounding to even" in {
        val op = thumbOp(0, "h", "4320", "webp", imageInfo(1600, 1063))
        assert(op == Some(ImageThumbnail(width = None, height = Some(1063), format = ImageFormat.Webp)))
      }

      "reject a video thumbnail when the timestamp does not match" in {
        val op = thumbOp(999, "w", "352", "webp", videoInfo(1920, 1080))
        assert(op.isEmpty)
      }

      "reject a thumbnail when the content version does not match" in {
        val info = videoInfo(1920, 1080)
        val op   = ResourceContentRoutes.patterns.thumbnailOperation("staleversion", 1000, "w", "352", "webp", resolutions, formats, info)
        assert(op.isEmpty)
      }
    }

    "building an AVIF thumbnail operation" should {

      "grow the pinned width so the derived side stays >= 64 on a 16:9 source" in {
        val op = thumbOp(1000, "w", "96", "avif", videoInfo(1920, 1080), smallResolutions)
        assert(op == Some(VideoThumbnail(width = Some(114), height = None, timestamp = 1000, format = ImageFormat.Avif)))
      }

      "leave the pinned width alone when the derived side already reaches 64 (3:2 source)" in {
        val op = thumbOp(1000, "w", "96", "avif", videoInfo(1920, 1280), smallResolutions)
        assert(op == Some(VideoThumbnail(width = Some(96), height = None, timestamp = 1000, format = ImageFormat.Avif)))
      }

      "leave a height-pinned AVIF thumbnail alone when the derived width reaches 64" in {
        val op = thumbOp(1000, "h", "96", "avif", videoInfo(1920, 1080), smallResolutions)
        assert(op == Some(VideoThumbnail(width = None, height = Some(96), timestamp = 1000, format = ImageFormat.Avif)))
      }

      "round the grown pinned width up to even when rounding down would break the minimum" in {
        val op = thumbOp(1000, "w", "96", "avif", videoInfo(1000, 600), smallResolutions)
        assert(op == Some(VideoThumbnail(width = Some(108), height = None, timestamp = 1000, format = ImageFormat.Avif)))
      }

      "not grow thumbnails for formats without a minimum side" in {
        val op = thumbOp(1000, "w", "96", "webp", videoInfo(1920, 1080), smallResolutions)
        assert(op == Some(VideoThumbnail(width = Some(96), height = None, timestamp = 1000, format = ImageFormat.Webp)))
      }

      "grow a width-pinned AVIF image thumbnail the same way" in {
        val op = thumbOp(0, "w", "96", "avif", imageInfo(1600, 900), smallResolutions)
        assert(op == Some(ImageThumbnail(width = Some(114), height = None, format = ImageFormat.Avif)))
      }
    }

    "building a clip operation" should {

      "build a clip for the canonical range and cap it at the source dimensions" in {
        val op = clipOp(1000, 4000, "w", "4320", videoInfo(1920, 1080))
        assert(op == Some(VideoFragment(width = Some(1920), height = None, start = 1000, end = 4000)))
      }

      "cap the clip end at the video length" in {
        val op = clipOp(1000, 2000, "w", "352", videoInfo(1920, 1080, durationInMillis = 2000))
        assert(op == Some(VideoFragment(width = Some(352), height = None, start = 1000, end = 2000)))
      }

      "reject a clip when the start does not match the canonical range" in {
        val op = clipOp(999, 4000, "w", "352", videoInfo(1920, 1080))
        assert(op.isEmpty)
      }

      "reject a clip when the end does not match the canonical range" in {
        val op = clipOp(1000, 3999, "w", "352", videoInfo(1920, 1080))
        assert(op.isEmpty)
      }

      "reject a clip when the content version does not match" in {
        val info = videoInfo(1920, 1080)
        val op   = ResourceContentRoutes.patterns.clipOperation("staleversion", 1000, 4000, "w", "352", resolutions, info)
        assert(op.isEmpty)
      }
    }

    "computing a content version" should {

      "be stable for identical resources" in
        assert(ResourceOperations.contentVersion(videoInfo(1920, 1080)) == ResourceOperations.contentVersion(videoInfo(1920, 1080)))

      "change when content fields change" in {
        val base = videoInfo(1920, 1080)
        assert(ResourceOperations.contentVersion(base) != ResourceOperations.contentVersion(base.copy(size = 2L)))
        assert(ResourceOperations.contentVersion(base) != ResourceOperations.contentVersion(base.copy(timeLastModified = Some(2L))))
        assert(ResourceOperations.contentVersion(base) != ResourceOperations.contentVersion(base.copy(fileKey = Some("(dev=1,ino=2)"))))
        assert(ResourceOperations.contentVersion(base) != ResourceOperations.contentVersion(base.copy(partialHash = Some("other"))))
      }

      "not change on rename" in {
        val base = videoInfo(1920, 1080)
        assert(ResourceOperations.contentVersion(base) == ResourceOperations.contentVersion(base.copy(path = "renamed.mp4")))
      }
    }

    "matching public URLs" should {

      "match thumbnail URLs with a version, numeric size and a format extension" in {
        val thumbnail = ResourceContentRoutes.patterns.PublicThumbnailPattern.findFirstMatchIn("thumb_abc123_2863_w_768.avif")
        assert(thumbnail.map(m => (m.group(1), m.group(2), m.group(3), m.group(4), m.group(5))) == Some(("abc123", "2863", "w", "768", "avif")))

        val clip = ResourceContentRoutes.patterns.PublicClipPattern.findFirstMatchIn("clip_abc123_2863_5863_h_512.mp4")
        assert(clip.map(m => (m.group(1), m.group(2), m.group(3), m.group(4), m.group(5))) == Some(("abc123", "2863", "5863", "h", "512")))

        assert(ResourceContentRoutes.patterns.PublicThumbnailPattern.findFirstMatchIn("thumb_abc123_2863_w_s.webp").isEmpty)
      }
    }
  }
}
