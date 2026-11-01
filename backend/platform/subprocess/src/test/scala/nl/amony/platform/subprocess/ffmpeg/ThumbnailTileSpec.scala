package nl.amony.platform.subprocess.ffmpeg

import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile

class ThumbnailTileSpec extends AnyWordSpecLike {

  private def tile(frameCount: Int, columns: Int = 5, intervalInMillis: Long = 250, quality: Int = 70) =
    ThumbnailTile(
      width            = 192,
      height           = 108,
      columns          = columns,
      frameCount       = frameCount,
      intervalInMillis = intervalInMillis,
      startInMillis    = 0,
      quality          = quality
    )

  "ThumbnailTile" should {

    "use all rows for a full sprite" in
      assert(tile(25).rows == 5)

    "drop unused rows from a partially filled sprite" in {
      assert(tile(10).rows == 2)
      assert(tile(5).rows == 1)
      assert(tile(1).rows == 1)
    }

    "reject frames that do not fit in the grid" in
      assertThrows[IllegalArgumentException](tile(26))

    "reject an invalid quality" in
      assertThrows[IllegalArgumentException](tile(25, quality = 101))

    "resample at exactly one frame per interval and stop at the planned frame count" in {
      assert(ThumbnailTile.filter(tile(10)) == "fps=1000/250,scale=192:108,tile=5x2:nb_frames=10")
      assert(ThumbnailTile.filter(tile(25)) == "fps=1000/250,scale=192:108,tile=5x5:nb_frames=25")
    }

    "express every configured interval as an exact fps ratio" in
      assert(ThumbnailTile.filter(tile(25, intervalInMillis = 10000)) == "fps=1000/10000,scale=192:108,tile=5x5:nb_frames=25")

    "scale on the GPU before downloading frames when hardware decoding is on" in
      assert(
        ThumbnailTile.filter(tile(10), Some(HardwareDecoding("/dev/dri/renderD128"))) ==
          "scale_vaapi=w=192:h=108,hwdownload,format=nv12,fps=1000/250,tile=5x2:nb_frames=10"
      )
  }
}
