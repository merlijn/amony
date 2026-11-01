package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

class VideoThumbnailTileSpec extends AnyWordSpecLike {

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
        assert(VideoThumbnailTile.widthFor(20736, 1920, 1080) == 192)
        assert(VideoThumbnailTile.heightFor(192, 1920, 1080) == 108)

        assert(VideoThumbnailTile.widthFor(20736, 1080, 1920) == 108)
        assert(VideoThumbnailTile.heightFor(108, 1080, 1920) == 192)

        assert(VideoThumbnailTile.widthFor(20736, 1000, 1000) == 144)
        assert(VideoThumbnailTile.heightFor(144, 1000, 1000) == 144)
      }

      "keep both dimensions even for odd source aspect ratios" in {
        val dimensions = List((1920, 1080), (1080, 1920), (1000, 601), (1920, 817), (817, 1920)).map { case (sourceWidth, sourceHeight) =>
          val width = VideoThumbnailTile.widthFor(20736, sourceWidth, sourceHeight)
          (width, VideoThumbnailTile.heightFor(width, sourceWidth, sourceHeight))
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
