package nl.amony.lib.files.watcher

import cats.effect.IO

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long) {
  def equalFileMeta(path: Path, attrs: BasicFileAttributes): Boolean =
    size == Files.size(path) && creationTime == attrs.creationTime().toMillis && modifiedTime == Files.getLastModifiedTime(path).toMillis
}

trait FileStore {
  def getByPath(path: Path): IO[Option[FileInfo]]
  def getByHash(path: Path): IO[Seq[FileInfo]]
  def getAll(): fs2.Stream[IO, FileInfo]
  def applyEvent(e: FileEvent): IO[Unit]
}
