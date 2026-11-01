package nl.amony.modules.resources.api

import scala.concurrent.duration.*

import org.scalatest.wordspec.AnyWordSpecLike

class ThumbnailTilesSpec extends AnyWordSpecLike {

  private val tiles = ThumbnailTiles(List(250.millis, 500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds), targetFrames = 200)

  "ThumbnailTiles" should {

    "pick the smallest ladder interval that is at least duration / target frames" in {
      assert(tiles.intervalInMillis(10000) == 250)
      assert(tiles.intervalInMillis(30000) == 250)
      assert(tiles.intervalInMillis(60000) == 500)
      assert(tiles.intervalInMillis(120000) == 1000)
      assert(tiles.intervalInMillis(300000) == 2000)
      assert(tiles.intervalInMillis(600000) == 5000)
      assert(tiles.intervalInMillis(1800000) == 10000)
    }

    "cap at the largest configured interval" in
      assert(tiles.intervalInMillis(3600000) == 10000)

    "sort and deduplicate the configured ladder" in {
      val configured = ThumbnailTiles(List(10.seconds, 250.millis, 1.second, 250.millis), targetFrames = 200)
      assert(configured.intervals == List(250L, 1000L, 10000L))
    }

    "reject an empty, non-positive or zero-target configuration" in {
      assertThrows[IllegalArgumentException](ThumbnailTiles(Nil, targetFrames = 200))
      assertThrows[IllegalArgumentException](ThumbnailTiles(List(0.seconds), targetFrames = 200))
      assertThrows[IllegalArgumentException](ThumbnailTiles(List(1.second), targetFrames = 0))
      assertThrows[IllegalArgumentException](ThumbnailTiles(List(1.second), targetFrames = 200, spriteWidth = 0))
    }

    "produce a stable, URL-safe recipe token for identical settings" in {
      val other = ThumbnailTiles(List(250.millis, 500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds), targetFrames = 200)
      assert(tiles.recipeToken == other.recipeToken)
      assert(tiles.recipeToken.matches("[a-z2-7]{8}"))
    }

    "change the recipe token when the sprite geometry or sampling changes" in {
      val larger  = ThumbnailTiles(List(250.millis, 500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds), targetFrames = 200, spriteWidth = 320)
      val coarser = ThumbnailTiles(List(250.millis, 500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds), targetFrames = 100)
      val finer   = ThumbnailTiles(List(250.millis, 500.millis, 1.second, 2.seconds, 5.seconds), targetFrames = 200)

      assert(tiles.recipeToken != larger.recipeToken)
      assert(tiles.recipeToken != coarser.recipeToken)
      assert(tiles.recipeToken != finer.recipeToken)
    }
  }
}
