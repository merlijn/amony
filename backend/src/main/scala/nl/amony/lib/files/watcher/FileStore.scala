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
   * Returns all files with the given partialHash. May return an empty list if no files with the given partialHash exist.
   *
   * @param partialHash
   * @return
   */
  def getByPartialHash(partialHash: String): IO[Seq[FileInfo]]

  def getAll(): fs2.Stream[IO, FileInfo]

  def getAllByPartialHash(): fs2.Stream[IO, (String, Set[FileInfo])]

  def size(): Int

  def applyEvent(e: FileEvent): IO[Unit]

  def insert(fileInfo: FileInfo): IO[Unit] = IO(applyEvent(FileAdded(fileInfo)))

class InMemoryFileStore extends FileStore:

  private val byPath             = new ConcurrentHashMap[Path, FileInfo]()
  private val byPartialHashIndex = new ConcurrentHashMap[String, Set[FileInfo]]()

  private def getPartialHashBucket(partialHash: String): Set[FileInfo] = byPartialHashIndex.getOrDefault(partialHash, Set.empty)

  override def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(Option(byPath.get(path)))

  override def getByPartialHash(partialHash: String): IO[Seq[FileInfo]] =
    IO.pure(Option(byPartialHashIndex.get(partialHash)).map(_.toSeq).getOrElse(Seq.empty))

  override def getAll(): fs2.Stream[IO, FileInfo] = fs2.Stream.emits(byPath.values.asScala.toSeq)

  override def getAllByPartialHash(): fs2.Stream[IO, (String, Set[FileInfo])] = fs2.Stream.emits(byPartialHashIndex.asScala.toSeq)
    .map { case (partialHash, files) => (partialHash, files) }

  def getAllSync() = byPath.values.asScala.toSeq

  override def size(): Int = byPath.size

  override def insert(fileInfo: FileInfo): IO[Unit] = IO(insertSync(fileInfo))

  override def applyEvent(e: FileEvent): IO[Unit] = IO(applyEventSync(e))

  def insertSync(fileInfo: FileInfo): Unit = applyEventSync(FileAdded(fileInfo))

  def insertAllSync(files: Iterable[FileInfo]): Unit = files.foreach(insertSync)

  def applyEventSync(e: FileEvent): Unit = e match
    case FileMetaChanged(fileInfo) => synchronized {
        byPath.put(fileInfo.path, fileInfo)
        val updatedPartialHashBucket = getPartialHashBucket(fileInfo.partialHash).filterNot(_.path == fileInfo.path) + fileInfo
        byPartialHashIndex.put(fileInfo.partialHash, updatedPartialHashBucket)
      }

    case FileAdded(fileInfo)          => synchronized {
        byPath.put(fileInfo.path, fileInfo)
        byPartialHashIndex.put(fileInfo.partialHash, getPartialHashBucket(fileInfo.partialHash) + fileInfo)
      }
    case FileDeleted(fileInfo)        => synchronized {
        byPath.remove(fileInfo.path)
        val updatedPartialHashBucket = getPartialHashBucket(fileInfo.partialHash).filterNot(_ == fileInfo)
        if updatedPartialHashBucket.isEmpty then byPartialHashIndex.remove(fileInfo.partialHash)
        else byPartialHashIndex.put(fileInfo.partialHash, updatedPartialHashBucket)
      }
    case FileMoved(fileInfo, oldPath) => synchronized {
        // this check is done to allow circular renames (e.g. a.txt -> b.txt -> a.txt)
        if Option(byPath.get(oldPath)).exists(_.partialHash == fileInfo.partialHash) then byPath.remove(oldPath)

        byPath.put(fileInfo.path, fileInfo)
        val updatedPartialHashBucket =
          getPartialHashBucket(fileInfo.partialHash).filterNot(f => f.path == oldPath && f.partialHash == fileInfo.partialHash) + fileInfo
        byPartialHashIndex.put(fileInfo.partialHash, updatedPartialHashBucket)
      }

object InMemoryFileStore:

  def empty = new InMemoryFileStore()

  def apply(files: Iterable[FileInfo]): InMemoryFileStore = {
    val store = new InMemoryFileStore()
    store.insertAllSync(files)
    store
  }
