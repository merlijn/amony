package nl.amony.modules.resources.api

import java.nio.file.Files

import cats.effect.IO

trait ResourceContent:
  def contentType: String
  def stream: fs2.Stream[IO, Byte]

object ResourceContent:
  def fromPath(path: java.nio.file.Path, contentType: Option[String]): ResourceContentWithRangeSupport =
    LocalFile(fs2.io.file.Path.fromNioPath(path), contentType.getOrElse("application/octet-stream"))

trait ResourceContentWithRangeSupport extends ResourceContent:
  def size: Long
  def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte]

case class LocalFile(path: fs2.io.file.Path, contentType: String) extends ResourceContentWithRangeSupport:

  private val defaultChunkSize: Int = 64 * 1024

  override def size: Long                                                = Files.size(path.toNioPath)
  override def stream: fs2.Stream[IO, Byte]                              = fs2.io.file.Files[IO].readAll(path, defaultChunkSize, fs2.io.file.Flags.Read)
  override def streamRange(start: Long, end: Long): fs2.Stream[IO, Byte] = fs2.io.file.Files[IO].readRange(path, defaultChunkSize, start, end)

case class Resource(
  info: ResourceInfo,
  content: ResourceContentWithRangeSupport
)
