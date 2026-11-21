package nl.amony.modules.resources.api

import java.nio.file.Files
import java.time.Instant

import cats.effect.IO
import org.http4s.Uri

/**
 * Where the content of a resource can be obtained: either streamed by the application itself or fetched directly from
 * an external location, such as a presigned object storage URL.
 */
enum ResourceContent:
  case Streamed(content: StreamedResource)
  case Redirect(location: Uri, expiresAt: Instant)

trait StreamedResource:
  def contentType: String
  def stream: fs2.Stream[IO, Byte]

  /**
   * A strong validator for conditional requests (HTTP ETag), or `None` when the content has no stable identity. The
   * value is opaque; the transport layer is responsible for rendering it as an `ETag` header.
   */
  def eTag: Option[String]

object StreamedResource:
  def fromPath(path: java.nio.file.Path, contentType: Option[String], eTag: Option[String] = None): StreamedResourceWithRangeSupport =
    LocalFile(fs2.io.file.Path.fromNioPath(path), contentType.getOrElse("application/octet-stream"), eTag)

trait StreamedResourceWithRangeSupport extends StreamedResource:
  def size: Long
  def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte]

case class LocalFile(path: fs2.io.file.Path, contentType: String, eTag: Option[String] = None) extends StreamedResourceWithRangeSupport:

  private val defaultChunkSize: Int = 64 * 1024

  override def size: Long                                                = Files.size(path.toNioPath)
  override def stream: fs2.Stream[IO, Byte]                              = fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)
  override def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte] = fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)
