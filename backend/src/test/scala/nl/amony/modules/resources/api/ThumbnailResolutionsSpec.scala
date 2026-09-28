package nl.amony.modules.resources.api

import org.scalatest.wordspec.AnyWordSpecLike

class ThumbnailResolutionsSpec extends AnyWordSpecLike {

  private val resolutions = ThumbnailResolutions(List(128, 256, 512, 1024), 512, stepDown = 0)

  "ThumbnailResolutions" should {

    "order sizes from smallest to largest" in {
      assert(resolutions.sizes == List(128, 256, 512, 1024))
    }

    "expose both dimensions" in {
      assert(resolutions.dimensions == List(ThumbnailDimension.Width, ThumbnailDimension.Height))
      assert(resolutions.dimensions.map(_.name) == List("width", "height"))
      assert(resolutions.dimensions.map(_.token) == List("w", "h"))
    }

    "serve the requested size when no step-down is configured" in {
      assert(resolutions.default == 512)
      assert(resolutions.effectiveSize(512) == 512)
    }

    "fall back to the default for unknown sizes" in {
      assert(resolutions.effectiveSize(999) == 512)
    }

    "step down the requested size by the configured number of rungs" in {
      val stepped = ThumbnailResolutions(List(128, 256, 512, 1024), 512, stepDown = 1)
      assert(stepped.effectiveSize(512) == 256)
      assert(stepped.effectiveSize(1024) == 512)
    }

    "not step below the smallest configured size" in {
      val stepped = ThumbnailResolutions(List(128, 256, 512, 1024), 512, stepDown = 5)
      assert(stepped.effectiveSize(512) == 128)
    }

    "resolve a dimension token and apply the step-down" in {
      val stepped = ThumbnailResolutions(List(128, 256, 512), 256, stepDown = 1)
      assert(stepped.resolve("w", 512) == (ThumbnailDimension.Width, 256))
      assert(stepped.resolve("h", 512) == (ThumbnailDimension.Height, 256))
    }

    "reject an empty configuration, an unknown default or a negative step-down" in {
      assertThrows[IllegalArgumentException](ThumbnailResolutions(Nil, 512, 0))
      assertThrows[IllegalArgumentException](ThumbnailResolutions(List(128, 256), 512, 0))
      assertThrows[IllegalArgumentException](ThumbnailResolutions(List(128, 256), 256, -1))
    }
  }
}
