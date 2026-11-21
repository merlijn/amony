package nl.amony.modules.resources.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourcePathSpec extends AnyWordSpecLike with Matchers:

  "ResourcePath.fromString" should {

    "accept plain and nested relative paths" in {
      ResourcePath.fromString("video.mp4") shouldBe Right(ResourcePath("video.mp4"))
      ResourcePath.fromString("sub/dir/video.mp4") shouldBe Right(ResourcePath("sub/dir/video.mp4"))
    }

    "reject blank paths" in {
      ResourcePath.fromString("") shouldBe a[Left[?, ?]]
      ResourcePath.fromString("   ") shouldBe a[Left[?, ?]]
    }

    "reject absolute paths" in {
      ResourcePath.fromString("/etc/passwd") shouldBe a[Left[?, ?]]
    }

    "reject bucket-escaping paths" in {
      ResourcePath.fromString("..") shouldBe a[Left[?, ?]]
      ResourcePath.fromString("../other.mp4") shouldBe a[Left[?, ?]]
      ResourcePath.fromString("sub/../../other.mp4") shouldBe a[Left[?, ?]]
    }
  }
