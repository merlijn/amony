package nl.amony.modules.resources.impl

import java.nio.file.{Files as JFiles, Path}
import java.util.UUID

import cats.effect.{IO, Resource}
import fs2.io.file.{Files, Path as Fs2Path}

import nl.amony.modules.resources.api.StagingId

final case class StagedFile(id: StagingId, path: Path, size: Long)

/** Temporary scratch space for in-flight uploads and remuxes. */
trait StagingArea:
  def write(id: StagingId, source: fs2.Stream[IO, Byte]): IO[StagedFile]
  def get(id: StagingId): IO[Option[StagedFile]]
  def prepare(id: StagingId): IO[Path]
  def tempFile(prefix: String, suffix: String = ""): Resource[IO, Path]
  def delete(id: StagingId): IO[Unit]

final class LocalStagingArea(directory: Path) extends StagingArea:

  override def write(id: StagingId, source: fs2.Stream[IO, Byte]): IO[StagedFile] =
    val target = directory.resolve(id)
    val write  =
      for
        _    <- IO.blocking(JFiles.createDirectories(directory))
        _    <- source.through(Files[IO].writeAll(Fs2Path.fromNioPath(target))).compile.drain
        size <- IO.blocking(JFiles.size(target))
      yield StagedFile(id, target, size)
    write.onError(_ => delete(id)).onCancel(delete(id))

  override def get(id: StagingId): IO[Option[StagedFile]] =
    IO.blocking {
      val path = directory.resolve(id)
      Option.when(JFiles.exists(path))(StagedFile(id, path, JFiles.size(path)))
    }

  override def prepare(id: StagingId): IO[Path] =
    IO.blocking {
      JFiles.createDirectories(directory)
      directory.resolve(id)
    }

  override def tempFile(prefix: String, suffix: String = ""): Resource[IO, Path] =
    Resource.make(
      IO.blocking {
        JFiles.createDirectories(directory)
        directory.resolve(s".$prefix.${UUID.randomUUID()}$suffix")
      }
    )(path => IO.blocking(JFiles.deleteIfExists(path)).void)

  override def delete(id: StagingId): IO[Unit] =
    IO.blocking(JFiles.deleteIfExists(directory.resolve(id))).void
