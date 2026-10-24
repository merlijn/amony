package nl.amony.platform.files.sync

import java.nio.file.Path

import nl.amony.platform.files.FileInfo

sealed trait FileEvent

case class FileMetaChanged(fileInfo: FileInfo) extends FileEvent

case class FileModified(fileInfo: FileInfo) extends FileEvent

case class FileAdded(fileInfo: FileInfo) extends FileEvent

case class FileDeleted(fileInfo: FileInfo) extends FileEvent

case class FileMoved(fileInfo: FileInfo, oldPath: Path) extends FileEvent

case class FileMovedAndModified(fileInfo: FileInfo, oldPath: Path) extends FileEvent
