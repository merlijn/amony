package nl.amony.modules.resources.api

import java.nio.file.{Files, Path, StandardCopyOption}

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StreamabilitySpec extends AnyWordSpecLike with Matchers {

  private def fixture(name: String): Path = {
    val stream   = Option(getClass.getResourceAsStream(s"/video/$name")).getOrElse(sys.error(s"Missing test fixture: $name"))
    val tempFile = Files.createTempFile("streamability-", "-" + name)
    try Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING)
    finally stream.close()
    tempFile.toFile.deleteOnExit()
    tempFile
  }

  private def detect(name: String): Streamability =
    Streamability.detect(fixture(name)).unsafeRunSync()

  "Streamability" when {

    "detect" should {

      "report an mp4 with its moov atom at the end as not streamable" in {
        detect("mp4_moov_at_end.mp4") shouldBe Streamability.NotStreamable
      }

      "report a faststart mp4 as streamable" in {
        detect("mp4_streamable.mp4") shouldBe Streamability.Streamable
      }

      "report a fragmented mp4 as streamable" in {
        detect("mp4_fragmented.mp4") shouldBe Streamability.Streamable
      }

      "report a webm with Cues at the end as not streamable" in {
        detect("webm_cues_at_end.webm") shouldBe Streamability.NotStreamable
      }

      "report a webm with Cues at the front as streamable" in {
        detect("webm_streamable.webm") shouldBe Streamability.Streamable
      }

      "report an mkv with Cues at the end as not streamable" in {
        detect("mkv_cues_at_end.mkv") shouldBe Streamability.NotStreamable
      }

      "report an mkv with Cues at the front as streamable" in {
        detect("mkv_streamable.mkv") shouldBe Streamability.Streamable
      }

      "report a non-video file as unknown" in {
        detect("not_a_video.bin") shouldBe Streamability.Unknown
      }
    }
  }
}
