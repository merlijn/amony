package nl.amony.modules.resources.api

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class VideoContainerSpec extends AnyFlatSpecLike with Matchers {

  "VideoContainer.fromContentType" should "resolve the supported containers" in {
    VideoContainer.fromContentType("video/mp4") shouldBe Some(VideoContainer.Mp4)
    VideoContainer.fromContentType("video/quicktime") shouldBe Some(VideoContainer.Mp4)
    VideoContainer.fromContentType("video/webm") shouldBe Some(VideoContainer.Webm)
    VideoContainer.fromContentType("video/x-matroska") shouldBe Some(VideoContainer.Matroska)
    VideoContainer.fromContentType("application/x-matroska") shouldBe Some(VideoContainer.Matroska)
  }

  it should "be case insensitive" in {
    VideoContainer.fromContentType("Video/WebM") shouldBe Some(VideoContainer.Webm)
  }

  it should "return None for unsupported content types" in {
    VideoContainer.fromContentType("video/x-msvideo") shouldBe None
    VideoContainer.fromContentType("video/x-ms-wmv") shouldBe None
    VideoContainer.fromContentType("image/png") shouldBe None
  }
}
