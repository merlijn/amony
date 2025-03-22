package nl.amony.lib.files.watcher

import cats.effect.IO

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

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
  def getByHash(hash: String): IO[Seq[FileInfo]]

  def getAll(): fs2.Stream[IO, FileInfo]

  def size(): Int

  def applyEvent(e: FileEvent): IO[Unit]
  
  def insert(fileInfo: FileInfo): IO[Unit] = IO(applyEvent(FileAdded(fileInfo)))


class InMemoryFileStore extends FileStore:
  
  private val byPath = new ConcurrentHashMap[Path, FileInfo]()
  private val byHashIndex = new ConcurrentHashMap[String, Set[FileInfo]]()

  private def getHashBucket(hash: String): Set[FileInfo] = byHashIndex.getOrDefault(hash, Set.empty)

  override def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(Option(byPath.get(path)))

  override def getByHash(hash: String): IO[Seq[FileInfo]] = IO.pure(Option(byHashIndex.get(hash)).map(_.toSeq).getOrElse(Seq.empty))
  
  override def getAll(): fs2.Stream[IO, FileInfo] = fs2.Stream.emits(byPath.values.asScala.toSeq)

  override def size(): Int = byPath.size

  override def insert(fileInfo: FileInfo): IO[Unit] = IO(insertSync(fileInfo))

  override def applyEvent(e: FileEvent): IO[Unit] = IO(applyEventSync(e))
  
  def insertSync(fileInfo: FileInfo): Unit = applyEventSync(FileAdded(fileInfo))
  
  def insertAllSync(files: Iterable[FileInfo]): Unit = files.foreach(insertSync)

  def applyEventSync(e: FileEvent): Unit = e match
    case FileAdded(fileInfo) =>
      synchronized {
        byPath.put(fileInfo.path, fileInfo)
        byHashIndex.put(fileInfo.hash, getHashBucket(fileInfo.hash) + fileInfo)
      }
    case FileDeleted(fileInfo) =>
      synchronized {
        byPath.remove(fileInfo.path)
        val updatedHashBucket = getHashBucket(fileInfo.hash).filterNot(_ == fileInfo)
        if (updatedHashBucket.isEmpty)
          byHashIndex.remove(fileInfo.hash)
        else
          byHashIndex.put(fileInfo.hash, updatedHashBucket)
      }
    case FileMoved(fileInfo, oldPath) =>
      synchronized {
        byPath.remove(oldPath)
        byPath.put(fileInfo.path, fileInfo)
        val updatedHashBucket = getHashBucket(fileInfo.hash).filterNot(_.path == oldPath) + fileInfo
        byHashIndex.put(fileInfo.hash, updatedHashBucket)
      }

object InMemoryFileStore:
  
  def empty = new InMemoryFileStore()
  
  def apply(files: Iterable[FileInfo]): InMemoryFileStore = {
    val store = new InMemoryFileStore()
    store.insertAllSync(files)
    store
  }
