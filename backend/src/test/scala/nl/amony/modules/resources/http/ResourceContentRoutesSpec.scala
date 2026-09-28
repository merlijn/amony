package nl.amony.modules.resources.http

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*

class ResourceContentRoutesSpec extends AnyWordSpecLike {

  private val resolutions = ThumbnailResolutions(Map("s" -> 352, "xxl" -> 4320), "s")

  private def videoInfo(width: Int, height: Int) = ResourceInfo(
    bucketId           = BucketId("test"),
    resourceId         = ResourceId("resource"),
    userId             = UserId("user"),
    path               = "video.mp4",
    size               = 1L,
    contentMeta        = Some(ResourceMeta("ffprobe/test", "{}", VideoProperties(width, height, 30f, 10000))),
    thumbnailTimestamp = Some(1000)
  )

  private def imageInfo(width: Int, height: Int) = ResourceInfo(
    bucketId    = BucketId("test"),
    resourceId  = ResourceId("resource"),
    userId      = UserId("user"),
    path        = "image.jpg",
    size        = 1L,
    contentMeta = Some(ResourceMeta("magick/test", "{}", ImageProperties(width, height)))
  )

  "ResourceContentRoutes.thumbnailOperation" should {

    "cap a width request at the source width instead of upscaling" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(1000, "w", "xxl", resolutions, videoInfo(1920, 1080))
      assert(op == Some(VideoThumbnail(width = Some(1920), height = None, quality = 23, timestamp = 1000)))
    }

    "cap a height request at the source height instead of upscaling" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(1000, "h", "xxl", resolutions, videoInfo(1920, 1080))
      assert(op == Some(VideoThumbnail(width = None, height = Some(1080), quality = 23, timestamp = 1000)))
    }

    "round video dimensions down to an even number" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(1000, "w", "xxl", resolutions, videoInfo(1921, 1081))
      assert(op == Some(VideoThumbnail(width = Some(1920), height = None, quality = 23, timestamp = 1000)))
    }

    "not cap a request smaller than the source" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(1000, "w", "s", resolutions, videoInfo(1920, 1080))
      assert(op == Some(VideoThumbnail(width = Some(352), height = None, quality = 23, timestamp = 1000)))
    }

    "cap image thumbnails without rounding to even" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(0, "h", "xxl", resolutions, imageInfo(1600, 1063))
      assert(op == Some(ImageThumbnail(width = None, height = Some(1063), quality = 0)))
    }

    "reject a video thumbnail when the timestamp does not match" in {
      val op = ResourceContentRoutes.patterns.thumbnailOperation(999, "w", "s", resolutions, videoInfo(1920, 1080))
      assert(op.isEmpty)
    }
  }

  "ResourceContentRoutes.clipOperation" should {

    "cap a clip at the source dimensions" in {
      val op = ResourceContentRoutes.patterns.clipOperation(1000, "w", "xxl", resolutions, videoInfo(1920, 1080))
      assert(op == Some(VideoFragment(width = Some(1920), height = None, start = 1000, end = 4000, quality = 23)))
    }
  }
}
