package nl.amony.modules.resources.api

import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceMetaSpec extends AnyWordSpecLike with Matchers {

  private val existing = ResourceMeta(
    contentType = Some("video/mp4"),
    streamable  = Some(false),
    properties  = Some(VideoProperties(1920, 1080, 30f, 5000)),
    toolScan    = Some(ToolScan("ffprobe/1", Json.obj()))
  )

  "ResourceMeta.updatedWith" should {

    "replace the scan and properties when a fresh scan succeeds" in {
      val fresh = ResourceMeta(
        contentType = Some("video/mp4"),
        streamable  = Some(true),
        properties  = Some(VideoProperties(1280, 720, 25f, 6000)),
        toolScan    = Some(ToolScan("ffprobe/2", Json.obj()))
      )

      existing.updatedWith(fresh) shouldBe fresh
    }

    "keep the existing scan when a fresh scan yields no tool output" in {
      val fresh = ResourceMeta(contentType = Some("video/mp4"), streamable = Some(true))

      existing.updatedWith(fresh) shouldBe existing.copy(streamable = Some(true))
    }

    "replace the metadata when the content type changes" in {
      val fresh = ResourceMeta(contentType = Some("audio/mpeg"))

      existing.updatedWith(fresh) shouldBe fresh
    }
  }
}
