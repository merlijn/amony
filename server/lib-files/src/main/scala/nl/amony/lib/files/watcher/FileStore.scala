package nl.amony.lib.files.watcher

import cats.effect.IO

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

trait FileStore:

  def getByPath(path: Path): IO[Option[FileInfo]]

  def getByHash(hash: String): IO[Seq[FileInfo]]

  def getAll(): fs2.Stream[IO, FileInfo]

  def size(): Int

  def applyEvent(e: FileEvent): IO[Unit]
  
  def insert(fileInfo: FileInfo): IO[Unit] = IO(applyEvent(FileAdded(fileInfo)))


class InMemoryFileStore() extends FileStore:
  
  private val files = new ConcurrentHashMap[Path, FileInfo]()
  private var byHashIndex: Map[String, Set[FileInfo]] = Map.empty

  override def getByPath(path: Path): IO[Option[FileInfo]] = IO.pure(Option(files.get(path)))

  override def getByHash(hash: String): IO[Seq[FileInfo]] = IO.pure(byHashIndex.get(hash).map(_.toSeq).getOrElse(Seq.empty))
  
  override def getAll(): fs2.Stream[IO, FileInfo] = fs2.Stream.emits(files.values.asScala.toSeq)

  override def size(): Int = files.size

  override def insert(fileInfo: FileInfo): IO[Unit] = IO(insertSync(fileInfo))

  override def applyEvent(e: FileEvent): IO[Unit] = IO(applyEventSync(e))
  
  def insertSync(fileInfo: FileInfo): Unit = applyEventSync(FileAdded(fileInfo))
  
  def insertAllSync(files: Iterable[FileInfo]): Unit = files.foreach(insertSync)

  def applyEventSync(e: FileEvent): Unit = e match
    case FileAdded(fileInfo) =>
      synchronized {
        files.put(fileInfo.path, fileInfo)
        byHashIndex = byHashIndex.updated(fileInfo.hash, byHashIndex.getOrElse(fileInfo.hash, Set.empty) + fileInfo)
      }
    case FileDeleted(fileInfo) =>
      synchronized {
        files.remove(fileInfo.path)
        byHashIndex = byHashIndex.updated(fileInfo.hash, byHashIndex.getOrElse(fileInfo.hash, Set.empty).filterNot(_ == fileInfo))
      }
    case FileMoved(fileInfo, oldPath) =>
      synchronized {
        files.remove(oldPath)
        files.put(fileInfo.path, fileInfo)
        byHashIndex = byHashIndex.updated(fileInfo.hash, byHashIndex.getOrElse(fileInfo.hash, Set.empty).filterNot(_.path == oldPath) + fileInfo)
      }

object InMemoryFileStore:
  
  val empty = new InMemoryFileStore()
  
  def apply(files: Iterable[FileInfo]): InMemoryFileStore = {
    val store = new InMemoryFileStore()
    store.insertAllSync(files)
    store
  }
