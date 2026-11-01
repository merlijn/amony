package nl.amony.platform.subprocess.ffmpeg

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile

class ThumbnailTileSpec extends AnyWordSpecLike {

  private def tile(frameCount: Int, columns: Int = 8, intervalInMillis: Long = 250) =
    ThumbnailTile(
      width            = 160,
      height           = 90,
      columns          = columns,
      frameCount       = frameCount,
      intervalInMillis = intervalInMillis,
      startInMillis    = 0
    )

  "ThumbnailTile" should {

    "use all rows for a full sprite" in
      assert(tile(64).rows == 8)

    "drop unused rows from a partially filled sprite" in {
      assert(tile(10).rows == 2)
      assert(tile(8).rows == 1)
      assert(tile(1).rows == 1)
    }

    "reject frames that do not fit in the grid" in
      assertThrows[IllegalArgumentException](tile(65))

    "resample at exactly one frame per interval and stop at the planned frame count" in {
      assert(ThumbnailTile.filter(tile(10)) == "fps=1000/250,scale=160:90,tile=8x2:nb_frames=10")
      assert(ThumbnailTile.filter(tile(64)) == "fps=1000/250,scale=160:90,tile=8x8:nb_frames=64")
    }

    "express every configured interval as an exact fps ratio" in
      assert(ThumbnailTile.filter(tile(64, intervalInMillis = 10000)) == "fps=1000/10000,scale=160:90,tile=8x8:nb_frames=64")
  }
}
