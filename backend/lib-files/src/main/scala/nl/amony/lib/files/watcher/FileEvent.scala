package nl.amony.lib.files.watcher

import java.nio.file.Path

sealed trait FileEvent

case class FileMetaChanged(fileInfo: FileInfo) extends FileEvent

case class FileAdded(fileInfo: FileInfo) extends FileEvent

case class FileDeleted(fileInfo: FileInfo) extends FileEvent

case class FileMoved(fileInfo: FileInfo, oldPath: Path) extends FileEvent
