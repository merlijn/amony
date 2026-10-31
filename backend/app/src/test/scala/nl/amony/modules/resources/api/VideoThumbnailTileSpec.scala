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

      "group frames into 8x8 sprites" in {
        assert(VideoThumbnailTile.spriteCount(640000, 10000) == 1)
        assert(VideoThumbnailTile.spriteCount(640001, 10000) == 2)
        assert(VideoThumbnailTile.spriteCount(6400000, 10000) == 10)
      }

      "report the frame window of each sprite" in {
        assert(VideoThumbnailTile.startInMillis(1, 10000) == 0)
        assert(VideoThumbnailTile.startInMillis(2, 10000) == 640000)
        assert(VideoThumbnailTile.frameCountInSprite(1, 650000, 10000) == 64)
        assert(VideoThumbnailTile.frameCountInSprite(2, 650000, 10000) == 1)
      }

      "round the derived height up to an even number" in {
        assert(VideoThumbnailTile.heightFor(96, 1920, 1080) == 54)
        assert(VideoThumbnailTile.heightFor(96, 1000, 601) == 58)
      }
    }

    "rendering the manifest" should {

      "reference a new sprite file for every 64 frames" in {
        val vtt = VideoThumbnailTile.renderWebVtt(
          durationInMillis = 650000,
          intervalInMillis = 10000,
          width            = 96,
          height           = 54,
          spriteUrl        = index => s"/sprite_$index.webp"
        )

        assert(vtt.startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:10.000\n/sprite_1.webp#xywh=0,0,96,54\n"))
        assert(vtt.contains("00:01:10.000 --> 00:01:20.000\n/sprite_1.webp#xywh=672,0,96,54"))
        assert(vtt.contains("00:01:20.000 --> 00:01:30.000\n/sprite_1.webp#xywh=0,54,96,54"))
        assert(vtt.contains("00:10:30.000 --> 00:10:40.000\n/sprite_1.webp#xywh=672,378,96,54"))
        assert(vtt.contains("00:10:40.000 --> 00:10:50.000\n/sprite_2.webp#xywh=0,0,96,54"))
        assert(vtt.linesIterator.count(_.contains("-->")) == 65)
      }
    }
  }
}
