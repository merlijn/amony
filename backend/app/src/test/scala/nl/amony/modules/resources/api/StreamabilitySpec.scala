package nl.amony.modules.resources.api

import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path, StandardCopyOption}

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
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

  private def detectOverHttp(name: String): Streamability = {
    val bytes  = Files.readAllBytes(fixture(name))
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      s"/$name",
      exchange => {
        val rangeHeader = Option(exchange.getRequestHeaders.getFirst("Range"))
        rangeHeader match {
          case Some(header) if header.startsWith("bytes=") =>
            val parts = header.stripPrefix("bytes=").split("-", 2)
            val start = parts(0).toInt
            val end   = math.min(bytes.length - 1, parts(1).toInt)
            val slice = java.util.Arrays.copyOfRange(bytes, start, end + 1)
            exchange.getResponseHeaders.add("Content-Range", s"bytes $start-$end/${bytes.length}")
            exchange.sendResponseHeaders(206, slice.length.toLong)
            val out   = exchange.getResponseBody
            out.write(slice)
            out.close()
          case _                                           =>
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            val out = exchange.getResponseBody
            out.write(bytes)
            out.close()
        }
      }
    )
    server.start()
    try {
      val uri = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/$name")
      Streamability.detect(uri).unsafeRunSync()
    } finally server.stop(0)
  }

  "Streamability" when {

    "detect (local file)" should {

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

    "detect (HTTP range URI)" should {

      "report an mp4 with its moov atom at the end as not streamable" in {
        detectOverHttp("mp4_moov_at_end.mp4") shouldBe Streamability.NotStreamable
      }

      "report a faststart mp4 as streamable" in {
        detectOverHttp("mp4_streamable.mp4") shouldBe Streamability.Streamable
      }

      "report a fragmented mp4 as streamable" in {
        detectOverHttp("mp4_fragmented.mp4") shouldBe Streamability.Streamable
      }

      "report a webm with Cues at the end as not streamable" in {
        detectOverHttp("webm_cues_at_end.webm") shouldBe Streamability.NotStreamable
      }

      "report a webm with Cues at the front as streamable" in {
        detectOverHttp("webm_streamable.webm") shouldBe Streamability.Streamable
      }

      "report an mkv with Cues at the end as not streamable" in {
        detectOverHttp("mkv_cues_at_end.mkv") shouldBe Streamability.NotStreamable
      }

      "report an mkv with Cues at the front as streamable" in {
        detectOverHttp("mkv_streamable.mkv") shouldBe Streamability.Streamable
      }

      "report a non-video file as unknown" in {
        detectOverHttp("not_a_video.bin") shouldBe Streamability.Unknown
      }
    }
  }
}
