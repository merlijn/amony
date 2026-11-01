package nl.amony.modules.resources.api

import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ContentMetaSpec extends AnyWordSpecLike with Matchers {

  private val existing = ContentMeta(
    contentType = Some("video/mp4"),
    streamable  = Some(false),
    properties  = Some(VideoProperties(1920, 1080, 30f, 5000)),
    toolScan    = Some(ToolScan("ffprobe/1", Json.obj()))
  )

  private def videoToolScan(extra: (String, Json)*): ToolScan =
    ToolScan(
      "ffprobe/1",
      Json.obj(
        "streams" -> Json.arr(
          Json.obj(
            (List(
              "index"          -> Json.fromInt(0),
              "codec_name"     -> Json.fromString("h264"),
              "codec_type"     -> Json.fromString("video"),
              "width"          -> Json.fromInt(1920),
              "height"         -> Json.fromInt(1080),
              "duration"       -> Json.fromString("10.0"),
              "avg_frame_rate" -> Json.fromString("30/1")
            ) ++ extra)*
          )
        )
      )
    )

  private def videoProperties(toolScan: ToolScan): VideoProperties =
    ContentProperties.from(toolScan).get.asInstanceOf[VideoProperties]

  "ContentProperties.from" should {

    "keep the coded dimensions when the video is not rotated" in {
      val properties = videoProperties(videoToolScan())

      (properties.width, properties.height) shouldBe (1920, 1080)
    }

    "use the displayed dimensions for a quarter-turn rotation" in {
      val rotated = videoProperties(videoToolScan("side_data_list" -> Json.arr(Json.obj("rotation" -> Json.fromInt(90)))))
      (rotated.width, rotated.height) shouldBe (1080, 1920)

      val rotatedCounterClockwise = videoProperties(videoToolScan("side_data_list" -> Json.arr(Json.obj("rotation" -> Json.fromInt(-90)))))
      (rotatedCounterClockwise.width, rotatedCounterClockwise.height) shouldBe (1080, 1920)
    }

    "keep the coded dimensions for a 180 degree rotation" in {
      val properties = videoProperties(videoToolScan("side_data_list" -> Json.arr(Json.obj("rotation" -> Json.fromInt(180)))))

      (properties.width, properties.height) shouldBe (1920, 1080)
    }

    "fall back to the legacy rotate tag" in {
      val properties = videoProperties(videoToolScan("tags" -> Json.obj("rotate" -> Json.fromString("90"))))

      (properties.width, properties.height) shouldBe (1080, 1920)
    }
  }

  "ContentMeta.updatedWith" should {

    "replace the scan and properties when a fresh scan succeeds" in {
      val fresh = ContentMeta(
        contentType = Some("video/mp4"),
        streamable  = Some(true),
        properties  = Some(VideoProperties(1280, 720, 25f, 6000)),
        toolScan    = Some(ToolScan("ffprobe/2", Json.obj()))
      )

      existing.updatedWith(fresh) shouldBe fresh
    }

    "keep the existing scan when a fresh scan yields no tool output" in {
      val fresh = ContentMeta(contentType = Some("video/mp4"), streamable = Some(true))

      existing.updatedWith(fresh) shouldBe existing.copy(streamable = Some(true))
    }

    "replace the metadata when the content type changes" in {
      val fresh = ContentMeta(contentType = Some("audio/mpeg"))

      existing.updatedWith(fresh) shouldBe fresh
    }
  }
}
