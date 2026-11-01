package nl.amony.platform.subprocess.ffmpeg

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.compiletime.uninitialized

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import sttp.client4.UriContext
import sttp.client4.basicRequest
import sttp.client4.httpclient.HttpClientSyncBackend
import sttp.model.StatusCode

import nl.amony.platform.subprocess.ffmpeg.tasks.ThumbnailTile
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessSettings}

/** Serves the faststart mp4 fixture over plain http with byte-range support, mimicking an S3 presigned URL. */
class FFMpegSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private val ffmpeg = new FFMpeg(ProcessSettings(), ProcessLimiter.unlimited)(using Meter.noop[IO], Tracer.noop[IO])

  private var fixtureBytes: Array[Byte] = Array.empty
  private var server: HttpServer        = uninitialized
  private var executor                  = Executors.newCachedThreadPool()
  private var port: Int                 = uninitialized
  private var workDir: Path             = uninitialized

  private val backend       = HttpClientSyncBackend()
  private val rangeRequests = new AtomicInteger(0)
  private val totalRequests = new AtomicInteger(0)
  private val contentType   = "video/mp4"

  override def beforeAll(): Unit = {
    val stream = Option(getClass.getResourceAsStream("/video/mp4_streamable.mp4")).getOrElse(sys.error("Missing test fixture"))
    try fixtureBytes = stream.readAllBytes()
    finally stream.close()

    workDir = Files.createTempDirectory("ffmpeg-url-")
    workDir.toFile.deleteOnExit()

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(executor)
    server.createContext("/video.mp4", (exchange: HttpExchange) => handle(exchange))
    server.start()
    port   = server.getAddress.getPort
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try {
      if server != null then server.stop(0)
      executor.shutdown()
      backend.close()
    } finally super.afterAll()
  }

  private def videoUri = java.net.URI.create(s"http://127.0.0.1:$port/video.mp4")

  private def handle(exchange: HttpExchange): Unit = {
    totalRequests.incrementAndGet()
    try {
      val rangeHeader          = Option(exchange.getRequestHeaders.getFirst("Range"))
      val (status, start, end) = parseRange(rangeHeader, fixtureBytes.length) match {
        case Some((s, e)) =>
          rangeRequests.incrementAndGet()
          (206, s, e)
        case None         => (200, 0, fixtureBytes.length - 1)
      }

      val body = fixtureBytes.slice(start, end + 1)
      exchange.getResponseHeaders.add("Content-Type", contentType)
      exchange.getResponseHeaders.add("Accept-Ranges", "bytes")
      if status == 206 then exchange.getResponseHeaders.add("Content-Range", s"bytes $start-$end/${fixtureBytes.length}")
      exchange.sendResponseHeaders(status, body.length)
      val out  = exchange.getResponseBody
      try out.write(body)
      finally out.close()
    } finally exchange.close()
  }

  private val rangePattern = raw"bytes=(\d+)-(\d*)".r

  private def parseRange(header: Option[String], length: Int): Option[(Int, Int)] =
    header.collect { case rangePattern(start, end) =>
      val s = start.toInt
      val e = if end.isEmpty then length - 1 else end.toInt.min(length - 1)
      if s < length && s <= e then Some((s, e)) else None
    }.flatten

  "FFMpeg" when {
    "input is served over http" should {

      "support full and ranged downloads" in {
        val full = backend.send(basicRequest.get(uri"http://127.0.0.1:$port/video.mp4").response(sttp.client4.asByteArray))
        full.code shouldBe StatusCode.Ok
        full.header("Accept-Ranges") shouldBe Some("bytes")
        full.body.map(_.length) shouldBe Right(fixtureBytes.length)

        val partial =
          backend.send(basicRequest.get(uri"http://127.0.0.1:$port/video.mp4").header("Range", "bytes=0-99").response(sttp.client4.asByteArray))
        partial.code shouldBe StatusCode.PartialContent
        partial.header("Content-Range") shouldBe Some(s"bytes 0-99/${fixtureBytes.length}")
        partial.body.map(_.length) shouldBe Right(100)
        partial.body.map(_.toSeq) shouldBe Right(fixtureBytes.take(100).toSeq)
      }

      // The tests below shell out to the ffmpeg/ffprobe binaries, which are not installed on the CI runners
      // (see .github/workflows/build.yml). They are ignored by default; run them explicitly where ffmpeg is available.
      "probe a video over http" ignore {
        val (output, _) = ffmpeg.ffprobe(videoUri).unsafeRunSync()

        val video = output.firstVideoStream.getOrElse(fail("expected a video stream"))
        video.codec_name shouldBe "h264"
        video.durationMillis should be > 0
      }

      "create a thumbnail from a video over http" ignore {
        val out = workDir.resolve("thumb.webp")

        ffmpeg.createThumbnail(videoUri, timestamp = 500, outputFile = out).unsafeRunSync()

        Files.size(out) should be > 0L
        val header = Files.readAllBytes(out).take(12)
        new String(header.take(4), StandardCharsets.US_ASCII) shouldBe "RIFF"
        new String(header.drop(8), StandardCharsets.US_ASCII) shouldBe "WEBP"
      }

      "create a thumbnail tile from a video over http" ignore {
        val out  = workDir.resolve("tile.webp")
        val tile = ThumbnailTile(width = 160, height = 90, columns = 8, frameCount = 1, intervalInMillis = 10000, startInMillis = 0)

        ffmpeg.createThumbnailTile(videoUri, out, tile).unsafeRunSync()

        Files.size(out) should be > 0L
      }

      "extract a clip from a video over http" ignore {
        val out = workDir.resolve("clip.mp4")

        ffmpeg.transcodeToMp4(videoUri, range = (0L, 1000L), outputFile = out).unsafeRunSync()

        Files.size(out) should be > 0L
        val (output, _) = ffmpeg.ffprobe(out.toUri).unsafeRunSync()
        val video       = output.firstVideoStream.getOrElse(fail("expected a video stream in the clip"))
        video.codec_name shouldBe "h264"
        video.durationMillis should be > 0
      }
    }
  }

}
