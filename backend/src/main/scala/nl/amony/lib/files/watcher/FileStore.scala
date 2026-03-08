package nl.amony.lib.files.watcher

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import cats.effect.IO

trait FileStore:

  /**
   * Returns the file info for the given path, if it exists.
   *
   * @param path
   * @return
   */
  def getByPath(path: Path): IO[Option[FileInfo]]

  /**
   * Returns all files with the given hash. May return an empty list if no files with the given hash exist.
   *
   * @param hash
   * @return
   */
  def getByDedupId(hash: String): IO[Seq[FileInfo]]

  def getAll(): fs2.Stream[IO, FileInfo]

  def getAllDedupId(): fs2.Stream[IO, (String, Set[FileInfo])]

  def size(): Int

  def applyEvent(e: FileEvent): IO[Unit]

  def insert(fileInfo: FileInfo): IO[Unit] = IO(applyEvent(FileAdded(fileInfo)))

class InMemoryFileStore extends FileStore:

  private val byPath         = new ConcurrentHashMap[Path, FileInfo]()
  private val byDedupIdIndex = new ConcurrentHashMap[String, Set[FileInfo]]()

  private def getDedupBucket(dedupId: String): Set[FileInfo] = byDedupIdIndex.getOrDefault(dedupId, Set.empty)

  override def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(Option(byPath.get(path)))

  override def getByDedupId(hash: String): IO[Seq[FileInfo]] = IO.pure(Option(byDedupIdIndex.get(hash)).map(_.toSeq).getOrElse(Seq.empty))

  override def getAll(): fs2.Stream[IO, FileInfo] = fs2.Stream.emits(byPath.values.asScala.toSeq)

  override def getAllDedupId(): fs2.Stream[IO, (String, Set[FileInfo])] = fs2.Stream.emits(byDedupIdIndex.asScala.toSeq)
    .map { case (hash, files) => (hash, files) }

  def getAllSync() = byPath.values.asScala.toSeq

  override def size(): Int = byPath.size

  override def insert(fileInfo: FileInfo): IO[Unit] = IO(insertSync(fileInfo))

  override def applyEvent(e: FileEvent): IO[Unit] = IO(applyEventSync(e))

  def insertSync(fileInfo: FileInfo): Unit = applyEventSync(FileAdded(fileInfo))

  def insertAllSync(files: Iterable[FileInfo]): Unit = files.foreach(insertSync)

  def applyEventSync(e: FileEvent): Unit = e match
    case FileMetaChanged(fileInfo) =>
      synchronized {
        byPath.put(fileInfo.path, fileInfo)
        val updatedHashBucket = getDedupBucket(fileInfo.dedupId).filterNot(_.path == fileInfo.path) + fileInfo
        byDedupIdIndex.put(fileInfo.dedupId, updatedHashBucket)
      }

    case FileAdded(fileInfo)          =>
      synchronized {
        byPath.put(fileInfo.path, fileInfo)
        byDedupIdIndex.put(fileInfo.dedupId, getDedupBucket(fileInfo.dedupId) + fileInfo)
      }
    case FileDeleted(fileInfo)        =>
      synchronized {
        byPath.remove(fileInfo.path)
        val updatedHashBucket = getDedupBucket(fileInfo.dedupId).filterNot(_ == fileInfo)
        if updatedHashBucket.isEmpty then byDedupIdIndex.remove(fileInfo.dedupId) else byDedupIdIndex.put(fileInfo.dedupId, updatedHashBucket)
      }
    case FileMoved(fileInfo, oldPath) =>
      synchronized {
        // This is done to allow circular renames (e.g. FileMoved(a.txt, b.txt) -> FileMoved(b.txt, a.txt))
        // We first remove the old path
        if Option(byPath.get(oldPath)).exists(_.dedupId == fileInfo.dedupId) then byPath.remove(oldPath)

        byPath.put(fileInfo.path, fileInfo)
        val updatedHashBucket = getDedupBucket(fileInfo.dedupId).filterNot(f => f.path == oldPath && f.dedupId == fileInfo.dedupId) + fileInfo
        byDedupIdIndex.put(fileInfo.dedupId, updatedHashBucket)
      }

object InMemoryFileStore:

  def empty = new InMemoryFileStore()

  def apply(files: Iterable[FileInfo]): InMemoryFileStore = {
    val store = new InMemoryFileStore()
    store.insertAllSync(files)
    store
  }
