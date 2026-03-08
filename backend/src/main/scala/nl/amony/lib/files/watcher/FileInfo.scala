package nl.amony.lib.files.watcher

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object FileInfo {

  def apply(path: Path, dedupId: String): FileInfo = FileInfo(path, Files.readAttributes(path, classOf[BasicFileAttributes]), dedupId)

  def apply(path: Path, attrs: BasicFileAttributes, dedupId: String): FileInfo =
    FileInfo(path, dedupId, attrs.size(), attrs.lastModifiedTime().toMillis)
}

case class FileInfo(path: Path, dedupId: String, size: Long, modifiedTime: Long) {
  def isSameFileMeta(other: FileInfo): Boolean = other.dedupId == dedupId && size == other.size && modifiedTime == other.modifiedTime
}
