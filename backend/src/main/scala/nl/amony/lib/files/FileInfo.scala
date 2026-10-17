package nl.amony.lib.files

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object FileInfo {

  def apply(path: Path, partialHash: String): FileInfo = FileInfo(path, Files.readAttributes(path, classOf[BasicFileAttributes]), partialHash)

  def apply(path: Path, attrs: BasicFileAttributes, partialHash: String): FileInfo =
    FileInfo(path, partialHash, attrs.size(), attrs.lastModifiedTime().toMillis, Option(attrs.fileKey()).map(_.toString))
}

case class FileInfo(path: Path, partialHash: String, size: Long, modifiedTime: Long, fileKey: Option[String] = None) {
  def isSameFileMeta(other: FileInfo): Boolean =
    other.partialHash == partialHash && size == other.size && modifiedTime == other.modifiedTime && fileKey == other.fileKey
}
