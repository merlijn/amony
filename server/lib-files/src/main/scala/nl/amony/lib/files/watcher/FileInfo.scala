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
  def equalFileMeta(attrs: BasicFileAttributes): Boolean =
    size == attrs.size() && creationTime == attrs.creationTime().toMillis && modifiedTime == attrs.lastModifiedTime().toMillis
    
}

/**
 *  path -> event
 * 
 */

trait FileStore:

  def getByPath(path: Path): IO[Option[FileInfo]]

  def getByHash(hash: String): IO[Seq[FileInfo]]

  def getAll(): fs2.Stream[IO, FileInfo]
  
  def size(): Int

  def applyEvent(e: FileEvent): FileStore


class InMemoryFileStore(files: Map[Path, FileInfo]) extends FileStore:
  private val byHash: Map[String, Seq[FileInfo]] = files.values.foldLeft(Map.empty) {
    (acc, info) => acc.updated(info.hash, acc.getOrElse(info.hash, Seq.empty) :+ info)
  }

  def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(files.get(path))
  def getByHash(hash: String): IO[Seq[FileInfo]] = IO.pure(byHash.get(hash).getOrElse(Seq.empty))
  def getAll(): fs2.Stream[IO, FileInfo] = fs2.Stream.emits(files.values.toSeq)
  def size(): Int = files.size

  def applyEvent(e: FileEvent): InMemoryFileStore = e match
    case FileAdded(fileInfo) =>
      new InMemoryFileStore(files + (fileInfo.path -> fileInfo))
    case FileDeleted(fileInfo) =>
      new InMemoryFileStore(files - fileInfo.path)
    case FileMoved(fileInfo, oldPath) =>
      val prev = files(oldPath)
      new InMemoryFileStore(files - oldPath + (fileInfo.path -> prev.copy(path = fileInfo.path))) // we keep the old metadata

object InMemoryFileStore:
  def apply(files: Seq[FileInfo]): InMemoryFileStore = new InMemoryFileStore(files.map(f => f.path -> f).toMap)