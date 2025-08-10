package nl.amony.lib.files.watcher

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes

object FileInfo {
  
  def apply(path: Path, hash: String): FileInfo = 
    FileInfo(path, Files.readAttributes(path, classOf[BasicFileAttributes]), hash)

  def apply(path: Path, attrs: BasicFileAttributes, hash: String): FileInfo =
    FileInfo(path, hash, attrs.size(), attrs.creationTime().toMillis, attrs.lastModifiedTime().toMillis)
}

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long) {
  def isSameFileMeta(attrs: BasicFileAttributes): Boolean =
    size == attrs.size() && creationTime == attrs.creationTime().toMillis && modifiedTime == attrs.lastModifiedTime().toMillis

  def isSameFileMeta(other: FileInfo): Boolean =
    other.hash == hash && size == other.size && creationTime == other.creationTime && modifiedTime == other.modifiedTime
}