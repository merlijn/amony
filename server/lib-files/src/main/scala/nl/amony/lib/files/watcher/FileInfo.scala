package nl.amony.lib.files.watcher

import cats.effect.IO

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes

object FileInfo {
  
  def apply(path: Path, hash: String): FileInfo = {
    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
    FileInfo(path, attrs, hash)
  }

  def apply(path: Path, attrs: BasicFileAttributes, hash: String): FileInfo =
    FileInfo(path, hash, attrs.size(), attrs.creationTime().toMillis, attrs.lastModifiedTime().toMillis)
}

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long) {
  def equalFileMeta(path: Path, attrs: BasicFileAttributes): Boolean =
    size == Files.size(path) && creationTime == attrs.creationTime().toMillis && modifiedTime == attrs.lastModifiedTime().toMillis
    
}

trait FileStore:

  def getByPath(path: Path): IO[Option[FileInfo]]
  def getByHash(hash: String): IO[Seq[FileInfo]]
  def getAll(): Map[Path, FileInfo]


class InMemoryFileStore(files: Map[Path, FileInfo]) extends FileStore:
  private val byHash: Map[String, Seq[FileInfo]] = files.values.foldLeft(Map.empty) {
    (acc, info) => acc.updated(info.hash, acc.getOrElse(info.hash, Seq.empty) :+ info)
  }

  def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(files.get(path))
  def getByHash(hash: String): IO[Seq[FileInfo]] = IO.pure(byHash.get(hash).getOrElse(Seq.empty))
  def getAll(): Map[Path, FileInfo] = files
