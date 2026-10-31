package nl.amony.modules.resources.http

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as NioPath}
import java.util.zip.GZIPInputStream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.headers.{ETag, `Content-Encoding`, `Content-Length`, `If-None-Match`}
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.ci.CIStringSyntax

import nl.amony.modules.resources.api.{LocalFile, ResourceContent}

class ResourceDirectivesSpec extends AnyWordSpecLike {

  private def tempFile(bytes: Int): NioPath = {
    val path = Files.createTempFile("resource-directives", ".webp")
    Files.write(path, Array.fill[Byte](bytes)(1))
    path
  }

  private def tempTextFile(content: String): NioPath = {
    val path = Files.createTempFile("resource-directives", ".vtt")
    Files.writeString(path, content)
    path
  }

  private def localFile(path: NioPath, eTag: Option[String]): ResourceContent =
    LocalFile(fs2.io.file.Path.fromNioPath(path), "image/webp", eTag)

  private def localTextFile(path: NioPath, eTag: Option[String]): ResourceContent =
    LocalFile(fs2.io.file.Path.fromNioPath(path), "text/vtt", eTag)

  private def bodyText(response: Response[IO]): String =
    response.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()

  private def gunzipText(bytes: Array[Byte]): String = {
    val input = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try new String(input.readAllBytes(), StandardCharsets.UTF_8)
    finally input.close()
  }

  private def request(headers: Header.ToRaw*): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/api/resources/media/resource/thumb.webp")).putHeaders(headers*)

  private val small = tempFile(16)

  "ResourceDirectives" when {

    "handling conditional requests" should {

      "return a 200 carrying the ETag when no conditional header is present" in {
        val response = ResourceDirectives.resourceContentsResponse(request(), localFile(small, Some("v1"))).unsafeRunSync()

        assert(response.status == Status.Ok)
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1"))
      }

      "return 304 when If-None-Match matches the validator" in {
        val response = ResourceDirectives
          .resourceContentsResponse(request(`If-None-Match`(EntityTag("v1"))), localFile(small, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.NotModified)
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1"))
      }

      "return 304 for If-None-Match: *" in {
        val response = ResourceDirectives
          .resourceContentsResponse(request(Header.Raw(ci"If-None-Match", "*")), localFile(small, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.NotModified)
      }

      "return 200 when If-None-Match does not match the validator" in {
        val response = ResourceDirectives
          .resourceContentsResponse(request(`If-None-Match`(EntityTag("other"))), localFile(small, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.Ok)
      }

      "omit the ETag and always return 200 when the content has no validator" in {
        val response = ResourceDirectives
          .resourceContentsResponse(request(`If-None-Match`(EntityTag("v1"))), localFile(small, None))
          .unsafeRunSync()

        assert(response.status == Status.Ok)
        assert(response.headers.get[ETag].isEmpty)
      }

      "prefer 304 over a range response when the validator matches" in {
        val large    = tempFile(2 * 1024 * 1024)
        val response = ResourceDirectives
          .resourceContentsResponse(request(`If-None-Match`(EntityTag("v1")), Header.Raw(ci"Range", "bytes=0-99")), localFile(large, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.NotModified)
      }
    }

    "compressing text responses" should {

      "gzip a VTT response when the client accepts gzip" in {
        val content  = "WEBVTT\n\n00:00:00.000 --> 00:00:10.000\nsprite.webp#xywh=0,0,96,54\n"
        val response = ResourceDirectives
          .resourceContentsResponse(request(Header.Raw(ci"Accept-Encoding", "gzip")), localTextFile(tempTextFile(content), Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.Ok)
        assert(response.headers.get[`Content-Encoding`].map(_.contentCoding) == Some(ContentCoding.gzip))
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1-gzip"))
        assert(response.headers.get(ci"Vary").map(_.head.value) == Some("Accept-Encoding"))
        assert(gunzipText(response.body.compile.to(Array).unsafeRunSync()) == content)
      }

      "use a separate validator for the gzip representation" in {
        val content  = "WEBVTT\n"
        val response = ResourceDirectives
          .resourceContentsResponse(
            request(Header.Raw(ci"Accept-Encoding", "gzip"), `If-None-Match`(EntityTag("v1-gzip"))),
            localTextFile(tempTextFile(content), Some("v1"))
          )
          .unsafeRunSync()

        assert(response.status == Status.NotModified)
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1-gzip"))
        assert(response.headers.get[`Content-Encoding`].map(_.contentCoding) == Some(ContentCoding.gzip))
      }

      "serve uncompressed text when gzip is not accepted" in {
        val content  = "WEBVTT\n"
        val response = ResourceDirectives
          .resourceContentsResponse(request(), localTextFile(tempTextFile(content), Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.Ok)
        assert(response.headers.get[`Content-Encoding`].isEmpty)
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1"))
        assert(response.headers.get(ci"Vary").map(_.head.value) == Some("Accept-Encoding"))
        assert(bodyText(response) == content)
      }

      "not gzip binary content" in {
        val response = ResourceDirectives
          .resourceContentsResponse(request(Header.Raw(ci"Accept-Encoding", "gzip")), localFile(small, Some("v1")))
          .unsafeRunSync()

        assert(response.headers.get[`Content-Encoding`].isEmpty)
        assert(response.headers.get(ci"Vary").isEmpty)
      }

      "not gzip a ranged text response" in {
        val large    = tempTextFile("x" * (2 * 1024 * 1024))
        val response = ResourceDirectives
          .resourceContentsResponse(
            request(Header.Raw(ci"Accept-Encoding", "gzip"), Header.Raw(ci"Range", "bytes=0-99")),
            localTextFile(large, Some("v1"))
          )
          .unsafeRunSync()

        assert(response.status == Status.PartialContent)
        assert(response.headers.get[`Content-Encoding`].isEmpty)
        assert(response.headers.get[`Content-Length`].map(_.length) == Some(100L))
      }
    }

    "serving a partial response" should {

      "include the ETag on a partial (range) response" in {
        val large    = tempFile(2 * 1024 * 1024)
        val response = ResourceDirectives
          .resourceContentsResponse(request(Header.Raw(ci"Range", "bytes=0-99")), localFile(large, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.PartialContent)
        assert(response.headers.get[ETag].map(_.tag.tag) == Some("v1"))
      }

      "report the length of the served range, not the full resource, on a partial response" in {
        val large    = tempFile(2 * 1024 * 1024)
        val response = ResourceDirectives
          .resourceContentsResponse(request(Header.Raw(ci"Range", "bytes=1000-1999")), localFile(large, Some("v1")))
          .unsafeRunSync()

        assert(response.status == Status.PartialContent)
        assert(response.headers.get[`Content-Length`].map(_.length) == Some(1000L))
      }
    }
  }
}
