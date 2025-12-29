package nl.amony.lib.files

import java.nio.file.Paths

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import nl.amony.lib.files.watcher.*

class DirectoryScannerSpec extends AnyWordSpec with Matchers {

  def toFileStore(files: Seq[FileInfo]): FileStore = InMemoryFileStore.apply(files)

  val random = scala.util.Random

  extension (fileInfo: FileInfo) {
    def renameTo(fileName: String) =
      fileInfo.copy(path = Paths.get(fileName))
  }

  val fileA = FileInfo(Paths.get("a"), hash = "a", size = 100, modifiedTime = 1)
  val fileB = FileInfo(Paths.get("b"), hash = "b", size = 200, modifiedTime = 2)
  val fileC = FileInfo(Paths.get("c"), hash = "c", size = 300, modifiedTime = 3)

  def randomFile() = {
    val id         = java.util.UUID.randomUUID().toString
    FileInfo(
      path         = Paths.get(id),
      hash         = id,
      size         = random.nextInt(100000) + 1,
      modifiedTime = random.nextInt(100000)
    )
  }

  def compare(previous: FileStore, current: FileStore): Seq[FileEvent] =
    LocalDirectoryScanner.compareFileStores(previous, current).compile.toList.unsafeRunSync()

  def compare(previous: Set[FileInfo], current: Set[FileInfo]): Set[FileEvent] = {
    val events = compare(InMemoryFileStore(previous), InMemoryFileStore(current)).toSet

    events
  }

  "DirectoryScanner" should {
    "detected added files" in {

      val currentFiles = Set(fileA, fileB, fileC)
      val previous     = Set.empty[FileInfo]

      val events = compare(previous, currentFiles)

      events shouldBe currentFiles.map(FileInfo => FileAdded(FileInfo))
    }

    "detect files with modified meta data" in {

      val currentFiles = Set(fileA, fileB, fileC)

      val previousFiles = Set(
        fileA.copy(size         = 200),
        fileB.copy(modifiedTime = 3),
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

      val currentFiles  = Set(fileA)
      val previousFiles = Set(fileA.copy(hash = "b"))

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileDeleted(fileA.copy(hash = "b")),
        FileAdded(fileA)
      )
    }

    "detected moved files (simple case)" in {

      val currentFiles = Set(fileA, fileB, fileC)

      val previousFiles = Set(
        fileA.renameTo("d"),
        fileB.renameTo("e"),
        fileC.renameTo("f")
      )

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileMoved(fileA, Paths.get("d")),
        FileMoved(fileB, Paths.get("e")),
        FileMoved(fileC, Paths.get("f"))
      )
    }

    "circular rename - 2 files" in {

      val previousFiles = Set(fileA, fileB)

      val renamedA = fileA.renameTo("b")
      val renamedB = fileB.renameTo("a")

      val currentFiles = Set(renamedA, renamedB)

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileMoved(renamedA, oldPath = Paths.get("a")),
        FileMoved(renamedB, oldPath = Paths.get("b"))
      )

      val fs = InMemoryFileStore(previousFiles)
      events.foreach(fs.applyEventSync)

      fs.getAllSync().toSet shouldBe currentFiles
      fs.getByHash("a").unsafeRunSync() shouldBe Seq(renamedA)
      fs.getByHash("b").unsafeRunSync() shouldBe Seq(renamedB)
    }

    "circular rename - 3 files" in {

      /**
       * We start with 3 files with different hashes, a, b and c.
       *
       * All are renamed to each other:
       *
       * - a -> b
       * - b -> c
       * - c -> a
       */
      val current = Set(fileA, fileB, fileC)

      val previous = Set(
        fileA.renameTo("b"),
        fileB.renameTo("c"),
        fileC.renameTo("a")
      )

      val events = compare(previous, current)

      events shouldBe Set(
        FileMoved(fileA, Paths.get("b")),
        FileMoved(fileB, Paths.get("c")),
        FileMoved(fileC, Paths.get("a"))
      )
    }

    "hash collision -> single rename = moved file" in {

      /**
       * We start with 3 files with the same hash, one of them is renamed.
       */
      val f = randomFile()

      val (a, b, c) = (f.renameTo("a"), f.renameTo("b"), f.renameTo("c"))

      val previous = Set(a, b, c)

      val d = c.renameTo("d")

      val current = Set(a, b, d)

      val events = compare(previous, current)

      events shouldBe Set(
        FileMoved(d, Paths.get("c"))
      )
    }

    "hash collision -> multiple renames = added + removed" in {

      /**
       * We start with 3 files with the same hash, two of them are renamed.
       */
      val f = randomFile()

      val (a, b, c) = (f.renameTo("a"), f.renameTo("b"), f.renameTo("c"))

      val previous = Set(a, b, c)

      val d = c.renameTo("d")
      val e = b.renameTo("e")

      val current = Set(a, d, e)

      val events = compare(previous, current)

      // since multiple files with the same hash but different paths are detected, we cannot tell which was moved where
      events shouldBe Set(FileDeleted(b), FileDeleted(c), FileAdded(d), FileAdded(e))
    }

    "moved + added (same name)" in {

      /**
       * We start with a single file a which is renamed it to c
       *
       * A new file with a different hash but the same name a is then added.
       */
      val previousFiles = Set(fileA)

      val renamedA      = fileA.copy(path = Paths.get("c"))
      val sameNameAdded = fileA.copy(hash = "newhash")

      val currentFiles = Set(renamedA, sameNameAdded)

      val events = compare(previousFiles, currentFiles)

      events shouldBe Set(
        FileAdded(sameNameAdded),
        FileMoved(renamedA, oldPath = Paths.get("a"))
      )

      val fs = InMemoryFileStore(previousFiles)
      events.foreach(fs.applyEventSync)

      fs.getAllSync().toSet shouldBe currentFiles
    }
  }
}
