package nl.amony.lib.files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import nl.amony.lib.files.watcher.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

class DirectoryScannerSpec extends AnyWordSpec with Matchers {

  def toFileStore(files: Seq[FileInfo]): FileStore = InMemoryFileStore.apply(files)

  def hashFunction(files: Seq[FileInfo])(path: java.nio.file.Path): cats.effect.IO[String] = IO {
    files.find(_.path == path) match
      case Some(f) => f.hash
      case None => throw new Exception("File not found")
  }

  val fileA = FileInfo(Paths.get("a.txt"), hash = "a", size = 100, creationTime = 1, modifiedTime = 1)
  val fileB = FileInfo(Paths.get("b.txt"), hash = "b", size = 200, creationTime = 2, modifiedTime = 2)
  val fileC = FileInfo(Paths.get("c.txt"), hash = "c", size = 300, creationTime = 3, modifiedTime = 3)

  def compare(previous: FileStore, current: FileStore): Set[FileEvent] =
    LocalDirectoryScanner.compareFileStores(previous, current).compile.toList.unsafeRunSync().toSet

  def compare(previous: Set[FileInfo], current: Set[FileInfo]): Set[FileEvent] =
    compare(InMemoryFileStore(previous), InMemoryFileStore(current))

  "DirectoryScanner" should {
    "detected added files" in {

      val currentFiles  = Set(fileA, fileB, fileC)
      val previous = InMemoryFileStore.empty

      val events = compare(previous, InMemoryFileStore(currentFiles))

      events shouldBe currentFiles.map(FileInfo => FileAdded(FileInfo))
    }

    "detect files with modified meta data" in {

        val currentFiles = Set(fileA, fileB, fileC)

        val previousFiles = Set(
          fileA.copy(size = 200),
          fileB.copy(creationTime = 3),
          fileC.copy(modifiedTime = 4)
        )

        val events = compare(previousFiles, currentFiles)

        events shouldBe Set(
          FileMetaChanged(fileA),
          FileMetaChanged(fileB),
          FileMetaChanged(fileC)
        )
    }

    "detect modified files" in {

      val currentFiles = Set(fileA)
      val previousFiles = Set(fileA.copy(hash = "b"))

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileDeleted(fileA.copy(hash = "b")), FileAdded(fileA)
      )
    }

    "detected moved files (simple case)" in {

      val currentFiles = Set(fileA, fileB, fileC)

      val previousFiles = Set(
        fileA.copy(path = Paths.get("d.txt")),
        fileB.copy(path = Paths.get("e.txt")),
        fileC.copy(path = Paths.get("f.txt"))
      )

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileMoved(fileA, Paths.get("d.txt")),
        FileMoved(fileB, Paths.get("e.txt")),
        FileMoved(fileC, Paths.get("f.txt"))
      )
    }

    "moved + added (same name)" in {

      val previousFiles = Set(fileA)

      val renamedA = fileA.copy(path = Paths.get("renamed.txt"))
      val sameNameAdded = fileA.copy(hash = "b")

      val currentFiles = Set(renamedA, sameNameAdded)

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileMoved(renamedA, Paths.get("a.txt")),
        FileAdded(sameNameAdded)
      )
    }

    "detected moved files (edge case)" in {

      val current = toFileStore(Seq(fileA, fileB, fileC))

      val previous = toFileStore(Seq(
        fileA.copy(path = Paths.get("b.txt")),
        fileB.copy(path = Paths.get("c.txt")),
        fileC.copy(path = Paths.get("a.txt"))
      ))

      val events = compare(previous, current)

      events shouldBe Set(
        FileMoved(fileA, Paths.get("b.txt")),
        FileMoved(fileB, Paths.get("c.txt")),
        FileMoved(fileC, Paths.get("a.txt"))
      )
    }

    "foo" ignore {

      def randomString(length: Int): String = scala.util.Random.alphanumeric.take(length).mkString

      val events = LocalDirectoryScanner.scanDirectory(
        directory = Paths.get("/Users/merlijn/dev/amony/media"),
        previous = new InMemoryFileStore(),
        directoryFilter = d => !d.getFileName.toString.startsWith("."),
        fileFilter = f => !f.getFileName.toString.startsWith("."),
        hashFunction = f => IO(randomString(8))
      ).compile.toList.unsafeRunSync()

      println(s"---\n${events.mkString}")
    }
  }
}
