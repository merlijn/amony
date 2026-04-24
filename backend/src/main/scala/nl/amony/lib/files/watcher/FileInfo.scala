package nl.amony.lib.files.watcher

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object FileInfo {

  def apply(path: Path, partialHash: String): FileInfo = FileInfo(path, Files.readAttributes(path, classOf[BasicFileAttributes]), partialHash)

  def apply(path: Path, attrs: BasicFileAttributes, partialHash: String): FileInfo =
    FileInfo(path, partialHash, attrs.size(), attrs.lastModifiedTime().toMillis)
}

case class FileInfo(path: Path, partialHash: String, size: Long, modifiedTime: Long) {
  def isSameFileMeta(other: FileInfo): Boolean = other.partialHash == partialHash && size == other.size && modifiedTime == other.modifiedTime
}
