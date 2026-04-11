package nl.amony.lib.files.tracking


case class BasicFileInfo(path: String, size: Long, modifiedTime: Long)

case class FileRecord(path: String, size: Long, modifiedTime: Long, dedupId: String, firstSeen: Long, lastSeen: Long)

trait FileTrackingStore:

  def getByPath(path: String): Option[FileRecord]

  def getAll(): Seq[FileRecord]

  def insert(record: FileRecord): Unit

  def update(record: FileRecord): Unit

  def delete(path: String): Unit

  def recordAsSeen(record: BasicFileInfo): Unit

  def markComplete(ts: Long): Unit
