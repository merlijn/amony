package nl.amony.lib.files.watcher

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object FileInfo {

  def apply(path: Path, hash: String): FileInfo = FileInfo(path, Files.readAttributes(path, classOf[BasicFileAttributes]), hash)

  def apply(path: Path, attrs: BasicFileAttributes, hash: String): FileInfo = FileInfo(path, hash, attrs.size(), attrs.lastModifiedTime().toMillis)
}

case class FileInfo(path: Path, hash: String, size: Long, modifiedTime: Long) {
  def isSameFileMeta(other: FileInfo): Boolean = other.hash == hash && size == other.size && modifiedTime == other.modifiedTime
}
