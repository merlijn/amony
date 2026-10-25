package nl.amony.modules.resources.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class VideoContainerSpec extends AnyWordSpecLike with Matchers {

  "VideoContainer" when {

    "fromContentType" should {

      "resolve the supported containers" in {
        VideoContainer.fromContentType("video/mp4") shouldBe Some(VideoContainer.Mp4)
        VideoContainer.fromContentType("video/quicktime") shouldBe Some(VideoContainer.Mp4)
        VideoContainer.fromContentType("video/webm") shouldBe Some(VideoContainer.Webm)
        VideoContainer.fromContentType("video/x-matroska") shouldBe Some(VideoContainer.Matroska)
        VideoContainer.fromContentType("application/x-matroska") shouldBe Some(VideoContainer.Matroska)
      }

      "be case insensitive" in {
        VideoContainer.fromContentType("Video/WebM") shouldBe Some(VideoContainer.Webm)
      }

      "return None for unsupported content types" in {
        VideoContainer.fromContentType("video/x-msvideo") shouldBe None
        VideoContainer.fromContentType("video/x-ms-wmv") shouldBe None
        VideoContainer.fromContentType("image/png") shouldBe None
      }
    }
  }
}
