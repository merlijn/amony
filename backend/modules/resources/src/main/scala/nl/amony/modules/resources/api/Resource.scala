package nl.amony.modules.resources.api

import java.nio.file.Files

import cats.effect.IO

trait ResourceContent:
  def contentType: String
  def stream: fs2.Stream[IO, Byte]

  /**
   * A strong validator for conditional requests (HTTP ETag), or `None` when the content has no stable identity. The
   * value is opaque; the transport layer is responsible for rendering it as an `ETag` header.
   */
  def eTag: Option[String]

object ResourceContent:
  def fromPath(path: java.nio.file.Path, contentType: Option[String], eTag: Option[String] = None): ResourceContentWithRangeSupport =
    LocalFile(fs2.io.file.Path.fromNioPath(path), contentType.getOrElse("application/octet-stream"), eTag)

trait ResourceContentWithRangeSupport extends ResourceContent:
  def size: Long
  def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte]

case class LocalFile(path: fs2.io.file.Path, contentType: String, eTag: Option[String] = None) extends ResourceContentWithRangeSupport:

  private val defaultChunkSize: Int = 64 * 1024

  override def size: Long                                                = Files.size(path.toNioPath)
  override def stream: fs2.Stream[IO, Byte]                              = fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)
  override def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte] = fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)

case class Resource(
  info: ResourceInfo,
  content: ResourceContentWithRangeSupport
)
