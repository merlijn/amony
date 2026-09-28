package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

class ThumbnailResolutionsSpec extends AnyWordSpecLike {

  private val resolutions = ThumbnailResolutions(Map("xxs" -> 144, "s" -> 352, "l" -> 1080), "s")

  "ThumbnailResolutions" should {

    "order sizes from smallest to largest" in {
      assert(resolutions.sizes.map(_.key) == List("xxs", "s", "l"))
      assert(resolutions.sizes.map(_.pixels) == List(144, 352, 1080))
    }

    "expose both dimensions" in {
      assert(resolutions.dimensions == List(ThumbnailDimension.Width, ThumbnailDimension.Height))
      assert(resolutions.dimensions.map(_.name) == List("width", "height"))
      assert(resolutions.dimensions.map(_.token) == List("w", "h"))
    }

    "fall back to the default size for unknown keys" in {
      assert(resolutions.pixelsFor("does-not-exist") == 352)
      assert(resolutions.default.key == "s")
      assert(resolutions.default.pixels == 352)
    }

    "resolve a dimension token and key" in {
      assert(resolutions.resolve("w", "l") == (ThumbnailDimension.Width, 1080))
      assert(resolutions.resolve("h", "xxs") == (ThumbnailDimension.Height, 144))
    }

    "reject an empty configuration or an unknown default key" in {
      assertThrows[IllegalArgumentException](ThumbnailResolutions(Map.empty, "s"))
      assertThrows[IllegalArgumentException](ThumbnailResolutions(Map("a" -> 100), "missing"))
    }
  }
}
