package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId

class ResourceOperationSpec extends AnyWordSpecLike {

  private val resolutions = ThumbnailResolutions(List(128, 256, 512), 256, stepDown = 0)
  private val formats     = ThumbnailFormats(List(ImageFormat.Webp, ImageFormat.Jpeg))

  private def videoInfo(
    width: Int,
    height: Int,
    durationInMillis: Int                     = 10000,
    thumbnailTimestamp: Int                   = 1000,
    pixelAspectRatio: Option[Float]           = None
  ) = ResourceInfo(
    bucketId           = BucketId("test"),
    resourceId         = ResourceId("resource"),
    userId             = UserId("user"),
    path               = ResourcePath("video.mp4"),
    partialHash        = "test-hash",
    size               = 1L,
    contentMeta        = ContentMeta.indexed(Some(VideoProperties(width, height, 30f, durationInMillis, pixelAspectRatio = pixelAspectRatio))),
    thumbnailTimestamp = Some(thumbnailTimestamp)
  )

  private def imageInfo(width: Int, height: Int) = ResourceInfo(
    bucketId    = BucketId("test"),
    resourceId  = ResourceId("resource"),
    userId      = UserId("user"),
    path        = ResourcePath("image.jpg"),
    partialHash = "test-hash",
    size        = 1L,
    contentMeta = ContentMeta.indexed(Some(ImageProperties(width, height)))
  )

  "ResourceOperation" when {

    "building all operations for a resource" should {

      "build a thumbnail per format and resolution plus a clip per resolution for videos" in {
        val operations = ResourceOperation.all(videoInfo(1920, 1080), resolutions, formats)

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

      "pin both dimensions for anamorphic videos" in {
        val operations = ResourceOperation.all(videoInfo(853, 480, pixelAspectRatio = Some(32f / 27f)), resolutions, formats)
        val thumbnails = operations.collect { case t: VideoThumbnail => t }
        val clips      = operations.collect { case f: VideoFragment => f }

        assert(thumbnails.forall(_.height.isDefined))
        assert(
          thumbnails.map(t => (t.width, t.height)).distinct == List(
            (Some(128), Some(72)),
            (Some(256), Some(144)),
            (Some(512), Some(288))
          )
        )
        assert(clips.map(c => (c.width, c.height)) == List((Some(128), Some(72)), (Some(256), Some(144)), (Some(512), Some(288))))
      }

      "build only image thumbnails for images" in {
        val operations = ResourceOperation.all(imageInfo(1600, 900), resolutions, formats)

        assert(operations.size == formats.formats.size * resolutions.sizes.size)
        assert(operations.forall(_.isInstanceOf[ImageThumbnail]))
        assert(operations.collect { case i: ImageThumbnail => i.width } == List(Some(128), Some(256), Some(512), Some(128), Some(256), Some(512)))
      }

      "return no operations when the content properties are unknown" in {
        val info = ResourceInfo(
          bucketId    = BucketId("test"),
          resourceId  = ResourceId("resource"),
          userId      = UserId("user"),
          path        = ResourcePath("file"),
          partialHash = "test-hash",
          size        = 1L
        )
        assert(ResourceOperation.all(info, resolutions, formats).isEmpty)
      }
    }
  }

  "VideoThumbnailTile" when {

    "planning frames" should {

      "always contain at least one frame" in {
        assert(VideoThumbnailTile.totalFrames(0, 10000) == 1)
        assert(VideoThumbnailTile.totalFrames(1000, 10000) == 1)
      }

      "contain one frame per interval, rounded up" in {
        assert(VideoThumbnailTile.totalFrames(10000, 10000) == 1)
        assert(VideoThumbnailTile.totalFrames(10001, 10000) == 2)
        assert(VideoThumbnailTile.totalFrames(35000, 10000) == 4)
      }

      "group frames into 5x5 sprites" in {
        assert(VideoThumbnailTile.spriteCount(250000, 10000) == 1)
        assert(VideoThumbnailTile.spriteCount(250001, 10000) == 2)
        assert(VideoThumbnailTile.spriteCount(2500000, 10000) == 10)
      }

      "report the frame window of each sprite" in {
        assert(VideoThumbnailTile.startInMillis(1, 10000) == 0)
        assert(VideoThumbnailTile.startInMillis(2, 10000) == 250000)
        assert(VideoThumbnailTile.frameCountInSprite(1, 260000, 10000) == 25)
        assert(VideoThumbnailTile.frameCountInSprite(2, 260000, 10000) == 1)
      }

      "derive the same target pixel area for landscape, portrait and square sources" in {
        assert(VideoThumbnailTile.resolutionFor(20480, 1920, 1080) == (192, 108))
        assert(VideoThumbnailTile.resolutionFor(20480, 1080, 1920) == (108, 192))
        assert(VideoThumbnailTile.resolutionFor(20480, 1000, 1000) == (144, 144))
      }

      "keep both dimensions even for odd source aspect ratios" in {
        val dimensions = List((1920, 1080), (1080, 1920), (1000, 601), (1920, 817), (817, 1920)).map {
          case (sourceWidth, sourceHeight) => VideoThumbnailTile.resolutionFor(20480, sourceWidth, sourceHeight)
        }

        assert(dimensions.forall((width, height) => width % 2 == 0 && height % 2 == 0))
      }
    }

    "rendering the manifest" should {

      "reference a new sprite file for every 25 frames" in {
        val vtt = VideoThumbnailTile.renderWebVtt(
          durationInMillis = 650000,
          intervalInMillis = 10000,
          width            = 192,
          height           = 108,
          spriteUrl        = index => s"/sprite_$index.webp"
        )

        assert(vtt.startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:10.000\n/sprite_1.webp#xywh=0,0,192,108\n"))
        assert(vtt.contains("00:01:10.000 --> 00:01:20.000\n/sprite_1.webp#xywh=384,108,192,108"))
        assert(vtt.contains("00:01:20.000 --> 00:01:30.000\n/sprite_1.webp#xywh=576,108,192,108"))
        assert(vtt.contains("00:04:00.000 --> 00:04:10.000\n/sprite_1.webp#xywh=768,432,192,108"))
        assert(vtt.contains("00:04:10.000 --> 00:04:20.000\n/sprite_2.webp#xywh=0,0,192,108"))
        assert(vtt.contains("00:10:30.000 --> 00:10:40.000\n/sprite_3.webp#xywh=576,216,192,108"))
        assert(vtt.contains("00:10:40.000 --> 00:10:50.000\n/sprite_3.webp#xywh=768,216,192,108"))
        assert(vtt.linesIterator.count(_.contains("-->")) == 65)
      }
    }
  }
}
