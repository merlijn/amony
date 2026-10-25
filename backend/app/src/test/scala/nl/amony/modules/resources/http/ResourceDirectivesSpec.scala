package nl.amony.modules.resources.http

import java.nio.file.{Files, Path as NioPath}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.headers.{ETag, `Content-Length`, `If-None-Match`}
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.ci.CIStringSyntax

import nl.amony.modules.resources.api.{LocalFile, ResourceContent}

class ResourceDirectivesSpec extends AnyWordSpecLike {

  private def tempFile(bytes: Int): NioPath = {
    val path = Files.createTempFile("resource-directives", ".webp")
    Files.write(path, Array.fill[Byte](bytes)(1))
    path
  }

  private def localFile(path: NioPath, eTag: Option[String]): ResourceContent =
    LocalFile(fs2.io.file.Path.fromNioPath(path), "image/webp", eTag)

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
