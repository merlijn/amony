package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId

class ResourceOperationsSpec extends AnyWordSpecLike {

  private val resolutions = ThumbnailResolutions(List(128, 256, 512), 256, stepDown = 0)
  private val formats     = ThumbnailFormats(List(ImageFormat.Webp, ImageFormat.Jpeg))

  private def videoInfo(width: Int, height: Int, durationInMillis: Int = 10000, thumbnailTimestamp: Int = 1000) = ResourceInfo(
    bucketId           = BucketId("test"),
    resourceId         = ResourceId("resource"),
    userId             = UserId("user"),
    path               = "video.mp4",
    size               = 1L,
    contentMeta        = Some(ResourceMeta("ffprobe/test", "{}", VideoProperties(width, height, 30f, durationInMillis))),
    thumbnailTimestamp = Some(thumbnailTimestamp)
  )

  private def imageInfo(width: Int, height: Int) = ResourceInfo(
    bucketId    = BucketId("test"),
    resourceId  = ResourceId("resource"),
    userId      = UserId("user"),
    path        = "image.jpg",
    size        = 1L,
    contentMeta = Some(ResourceMeta("magick/test", "{}", ImageProperties(width, height)))
  )

  "ResourceOperations.all" should {

    "build a thumbnail per format and resolution plus a clip per resolution for videos" in {
      val operations = ResourceOperations.all(videoInfo(1920, 1080), resolutions, formats)

      val thumbnails = operations.collect { case t: VideoThumbnail => t }
      val clips      = operations.collect { case f: VideoFragment => f }

      assert(thumbnails.size == formats.formats.size * resolutions.sizes.size)
      assert(thumbnails.map(_.width) == List(Some(128), Some(256), Some(512), Some(128), Some(256), Some(512)))
      assert(thumbnails.map(_.format).distinct == formats.formats)

      assert(clips.size == resolutions.sizes.size)
      assert(clips.map(_.width) == List(Some(128), Some(256), Some(512)))
      assert(clips.forall(_.start == 1000L))
      assert(clips.forall(_.end == 4000L))
    }

    "build only image thumbnails for images" in {
      val operations = ResourceOperations.all(imageInfo(1600, 900), resolutions, formats)

      assert(operations.size == formats.formats.size * resolutions.sizes.size)
      assert(operations.forall(_.isInstanceOf[ImageThumbnail]))
      assert(operations.collect { case i: ImageThumbnail => i.width } == List(Some(128), Some(256), Some(512), Some(128), Some(256), Some(512)))
    }

    "return no operations when the content properties are unknown" in {
      val info = ResourceInfo(bucketId = BucketId("test"), resourceId = ResourceId("resource"), userId = UserId("user"), path = "file", size = 1L)
      assert(ResourceOperations.all(info, resolutions, formats).isEmpty)
    }
  }
}
