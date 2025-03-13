package nl.amony.lib.files

import cats.effect.IO
import nl.amony.lib.files.watcher.*
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers

class DirectoryScannerSpec extends AnyWordSpecLike with Matchers {

  case class LocalFileMeta(override val size: Long, creationTimeMillis: Long, modifiedTimeMillis: Long) extends BasicFileAttributes {
    override def fileKey(): AnyRef = null
    override def lastAccessTime(): java.nio.file.attribute.FileTime = null
    override def lastModifiedTime(): java.nio.file.attribute.FileTime = FileTime.fromMillis(modifiedTimeMillis)
    override def creationTime(): java.nio.file.attribute.FileTime = FileTime.fromMillis(creationTimeMillis)
    override def isRegularFile(): Boolean = true
    override def isDirectory(): Boolean = false
    override def isSymbolicLink(): Boolean = false
    override def isOther(): Boolean = false
  }

  def toAttributes(files: Seq[FileInfo]): fs2.Stream[IO, (java.nio.file.Path, BasicFileAttributes)] = 
    fs2.Stream.emits(files.map { f => f.path -> LocalFileMeta(f.size, f.creationTime, f.modifiedTime) })

  def toFileStore(files: Seq[FileInfo]): FileStore = new InMemoryFileStore(files.map(f => f.path -> f).toMap)

  def hashFunction(files: Seq[FileInfo])(path: java.nio.file.Path): cats.effect.IO[String] = IO {
    files.find(_.path == path) match
      case Some(f) => f.hash
      case None => throw new Exception("File not found")
  }

  val fileA = FileInfo(Paths.get("a.txt"), hash = "a", size = 100, creationTime = 1, modifiedTime = 1)
  val fileB = FileInfo(Paths.get("b.txt"), hash = "b", size = 200, creationTime = 2, modifiedTime = 2)
  val fileC = FileInfo(Paths.get("c.txt"), hash = "c", size = 300, creationTime = 3, modifiedTime = 3)

  "DirectoryScanner" should {
    "detected added files" in {

      val currentFiles = Seq(fileA, fileB, fileC)
      val previous = new InMemoryFileStore(Map.empty)

      val events = LocalDirectoryScanner.scanDirectory2(toFileStore(currentFiles), previous)

      events.compile.toList.unsafeRunSync() shouldBe currentFiles.map(FileInfo => FileAdded(FileInfo))
    }

    "detect files with modified meta data" in {

        val currentFiles = Seq(fileA, fileB, fileC)

        val previousFiles = Seq(
          fileA.copy(size = 200),
          fileB.copy(creationTime = 3),
          fileC.copy(modifiedTime = 4)
        )

        val fileStore = InMemoryFileStore(previousFiles)
        val events = LocalDirectoryScanner.scanDirectory2(toFileStore(currentFiles), fileStore)

        events.compile.toList.unsafeRunSync() shouldBe Seq(
          FileMetaChanged(fileA),
          FileMetaChanged(fileB),
          FileMetaChanged(fileC)
        )
    }

    "detected moved files (simple case)" in {

      val currentFiles = Seq(fileA, fileB, fileC)

      val previousFiles = Seq(
        fileA.copy(path = Paths.get("d.txt")),
        fileB.copy(path = Paths.get("e.txt")),
        fileC.copy(path = Paths.get("f.txt"))
      )

      val fileStore = InMemoryFileStore(previousFiles)
      val events = LocalDirectoryScanner.scanDirectory2(toFileStore(currentFiles), fileStore)

      events.compile.toList.unsafeRunSync() shouldBe Seq(
        FileMoved(fileA, Paths.get("d.txt")),
        FileMoved(fileB, Paths.get("e.txt")),
        FileMoved(fileC, Paths.get("f.txt"))
      )
    }

    "detected moved files (edge case)" in {

      val current = toFileStore(Seq(fileA, fileB, fileC))

      val previous = toFileStore(Seq(
        fileA.copy(path = Paths.get("b.txt")),
        fileB.copy(path = Paths.get("c.txt")),
        fileC.copy(path = Paths.get("a.txt"))
      ))

      val events = LocalDirectoryScanner.scanDirectory2(current, previous)

      events.compile.toList.unsafeRunSync() shouldBe Seq(
        FileMoved(fileA, Paths.get("b.txt")),
        FileMoved(fileB, Paths.get("c.txt")),
        FileMoved(fileC, Paths.get("a.txt"))
      )
    }
  }
}
