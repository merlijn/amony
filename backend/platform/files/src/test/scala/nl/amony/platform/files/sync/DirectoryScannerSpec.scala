package nl.amony.platform.files.sync

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.util.{Comparator, HexFormat}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import nl.amony.platform.files.FileInfo

class DirectoryScannerSpec extends AnyWordSpec with Matchers {

  def toFileStore(files: Seq[FileInfo]): FileStore = InMemoryFileStore.apply(files)

  val random = scala.util.Random

  extension (fileInfo: FileInfo) {
    def renameTo(fileName: String) =
      fileInfo.copy(path = Paths.get(fileName))
  }

  val fileA = FileInfo(Paths.get("a"), partialHash = "a", size = 100, modifiedTime = 1)
  val fileB = FileInfo(Paths.get("b"), partialHash = "b", size = 200, modifiedTime = 2)
  val fileC = FileInfo(Paths.get("c"), partialHash = "c", size = 300, modifiedTime = 3)

  def randomFile() = {
    val id         = java.util.UUID.randomUUID().toString
    FileInfo(
      path         = Paths.get(id),
      partialHash  = id,
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

  def hashFile(path: java.nio.file.Path): IO[String] =
    IO.blocking(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path))))

  "DirectoryScanner" when {
    "comparing file stores" should {
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
        val previousFiles = Set(fileA.copy(partialHash = "b"))

        val events = compare(previousFiles, currentFiles)

        events shouldBe Set(
          FileDeleted(fileA.copy(partialHash = "b")),
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
        fs.getByPartialHash("a").unsafeRunSync() shouldBe Seq(renamedA)
        fs.getByPartialHash("b").unsafeRunSync() shouldBe Seq(renamedB)
      }

      "circular rename - 3 files" in {

        /**
       * We start with 3 files with different partialHashs, a, b and c.
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

      "partialHash collision -> single rename = moved file" in {

        /**
       * We start with 3 files with the same partialHash, one of them is renamed.
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

      "partialHash collision -> multiple renames = added + removed" in {

        /**
       * We start with 3 files with the same partialHash, two of them are renamed.
       */
        val f = randomFile()

        val (a, b, c) = (f.renameTo("a"), f.renameTo("b"), f.renameTo("c"))

        val previous = Set(a, b, c)

        val d = c.renameTo("d")
        val e = b.renameTo("e")

        val current = Set(a, d, e)

        val events = compare(previous, current)

        // since multiple files with the same partialHash but different paths are detected, we cannot tell which was moved where
        events shouldBe Set(FileDeleted(b), FileDeleted(c), FileAdded(d), FileAdded(e))
      }

      "moved + added (same name)" in {

        /**
       * We start with a single file a which is renamed it to c
       *
       * A new file with a different partialHash but the same name a is then added.
       */
        val previousFiles = Set(fileA)

        val renamedA      = fileA.copy(path = Paths.get("c"))
        val sameNameAdded = fileA.copy(partialHash = "newpartialHash")

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

      "backfill fileKey for legacy entries via FileMetaChanged" in {

        /**
       * Rows written before the file_key column existed load with fileKey = None (see toFileStore),
       * while the scan observes a key. The first scan must report the difference once, then converge.
       */
        val legacy  = fileA
        val current = fileA.copy(fileKey = Some("(dev=1,ino=1)"))

        compare(Set(legacy), Set(current)) shouldBe Set(FileMetaChanged(current))

        val fs = InMemoryFileStore(Set(legacy))
        compare(Set(legacy), Set(current)).foreach(fs.applyEventSync)

        fs.getByFileKey("(dev=1,ino=1)").unsafeRunSync() shouldBe Seq(current)
        compare(fs, InMemoryFileStore(Set(current))) shouldBe empty
      }

      "report content change for same path and fileKey with new hash" in {
        val previous = fileA.copy(fileKey = Some("(dev=1,ino=1)"))
        val edited   = fileA.copy(partialHash = "new", size = 101, modifiedTime = 2, fileKey = Some("(dev=1,ino=1)"))

        compare(Set(previous), Set(edited)) shouldBe Set(FileModified(edited))
      }

      "fall through to delete and add for same path with different fileKey" in {
        val previous = fileA.copy(partialHash = "old", fileKey = Some("(dev=1,ino=1)"))
        val replaced = fileA.copy(partialHash = "new", fileKey = Some("(dev=1,ino=2)"))

        compare(Set(previous), Set(replaced)) shouldBe Set(FileDeleted(previous), FileAdded(replaced))
      }

      "fall back to delete and add for content change without fileKeys" in {
        // without fileKeys (e.g. Windows) an in-place edit is indistinguishable from a replacement
        val previous = FileInfo(Paths.get("a"), partialHash = "old", size = 100, modifiedTime = 1)
        val edited   = FileInfo(Paths.get("a"), partialHash = "new", size = 200, modifiedTime = 2)

        compare(Set(previous), Set(edited)) shouldBe Set(FileDeleted(previous), FileAdded(edited))
      }

      "report move with modification for different path with same fileKey" in {
        val previous = fileA.copy(partialHash = "old", size = 100, modifiedTime = 1, fileKey = Some("(dev=1,ino=1)"))
        val current  = fileA.copy(path = Paths.get("b"), partialHash = "new", size = 200, modifiedTime = 2, fileKey = Some("(dev=1,ino=1)"))

        compare(Set(previous), Set(current)) shouldBe Set(FileMovedAndModified(current, Paths.get("a")))
      }

      "not pair moves with modification for ambiguous key matches" in {
        val key = Some("(dev=1,ino=1)")

        val previous = Set(
          FileInfo(Paths.get("a"), partialHash = "old", size = 100, modifiedTime = 1, fileKey = key),
          FileInfo(Paths.get("b"), partialHash = "old", size = 200, modifiedTime = 2, fileKey = key)
        )
        val current  = Set(
          FileInfo(Paths.get("c"), partialHash = "new", size = 300, modifiedTime = 3, fileKey = key)
        )

        val events = compare(previous, current)

        events shouldBe Set(
          FileDeleted(FileInfo(Paths.get("a"), partialHash = "old", size = 100, modifiedTime = 1, fileKey = key)),
          FileDeleted(FileInfo(Paths.get("b"), partialHash = "old", size = 200, modifiedTime = 2, fileKey = key)),
          FileAdded(FileInfo(Paths.get("c"), partialHash = "new", size = 300, modifiedTime = 3, fileKey = key))
        )
      }

      "fall through to delete and add for moved and modified files without fileKeys" in {
        val previous = FileInfo(Paths.get("a"), partialHash = "old", size = 100, modifiedTime = 1)
        val current  = FileInfo(Paths.get("b"), partialHash = "new", size = 200, modifiedTime = 2)

        compare(Set(previous), Set(current)) shouldBe Set(FileDeleted(previous), FileAdded(current))
      }

      "apply move with modification to the file store" in {
        val previous = fileA.copy(partialHash = "old", size = 100, modifiedTime = 1, fileKey = Some("(dev=1,ino=1)"))
        val current  = fileA.copy(path = Paths.get("b"), partialHash = "new", size = 200, modifiedTime = 2, fileKey = Some("(dev=1,ino=1)"))

        val fs = InMemoryFileStore(Set(previous))
        fs.applyEventSync(FileMovedAndModified(current, Paths.get("a")))

        fs.getAllSync().toSet shouldBe Set(current)
        fs.getByFileKey("(dev=1,ino=1)").unsafeRunSync() shouldBe Seq(current)
        fs.getByPartialHash("old").unsafeRunSync() shouldBe empty
        compare(fs, InMemoryFileStore(Set(current))) shouldBe empty
      }

    }

    "scanning real directories" should {

      "scan a real directory with parallel hashing" in {
        val dir = Files.createTempDirectory("scanner-test")
        try {
          val paths = (1 to 10).map(i => Files.write(dir.resolve(s"file-$i.bin"), Array.fill(i * 100)(i.toByte)))

          val events =
            LocalDirectoryScanner.scanDirectory(dir, InMemoryFileStore.empty, _ => true, _ => true, hashFile, parallelism = 4).compile.toList
              .unsafeRunSync()

          events.collect { case FileAdded(f) => f.path }.toSet shouldBe paths.toSet
          events.foreach(_.asInstanceOf[FileAdded].fileInfo.fileKey shouldBe defined)
        } finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }

      "reuse hash on rename via fileKey without rehashing" in {
        val dir = Files.createTempDirectory("scanner-rename-test")
        try {
          val original = Files.write(dir.resolve("original.bin"), Array.fill(2000)(7.toByte))
          val previous = InMemoryFileStore.empty

          val added =
            LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, hashFile, parallelism = 4).compile.toList
              .unsafeRunSync()
          added should have size 1
          added.foreach(previous.applyEventSync)

          val renamed = dir.resolve("renamed.bin")
          Files.move(original, renamed)

          def explodingHash(path: java.nio.file.Path): IO[String] =
            IO.raiseError(new RuntimeException(s"hash must be skipped for renamed file: $path"))

          val events =
            LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, explodingHash, parallelism = 4).compile.toList
              .unsafeRunSync()

          events should have size 1
          val FileMoved(moved, oldPath) = events.head: @unchecked
          moved.path shouldBe renamed
          oldPath shouldBe original
        } finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }

      "detect move with modification of a real file" in {
        val dir = Files.createTempDirectory("scanner-move-modify-test")
        try {
          val original = Files.write(dir.resolve("original.bin"), Array.fill(2000)(7.toByte))
          val previous = InMemoryFileStore.empty

          val added =
            LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, hashFile, parallelism = 4).compile.toList
              .unsafeRunSync()
          added should have size 1
          added.foreach(previous.applyEventSync)

          val renamed = dir.resolve("renamed.bin")
          Files.move(original, renamed)
          Files.write(renamed, Array.fill(500)(8.toByte), StandardOpenOption.APPEND)

          val events =
            LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, hashFile, parallelism = 4).compile.toList
              .unsafeRunSync()

          events should have size 1
          val FileMovedAndModified(moved, oldPath) = events.head: @unchecked
          moved.path shouldBe renamed
          oldPath shouldBe original
        } finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }

      "rehash when the fileKey changed but size and modifiedTime did not" in {
        // same path, size and modifiedTime would take the shortcut, but a different key (e.g. replaced via cp -p) must force a rehash
        val dir = Files.createTempDirectory("scanner-key-mismatch-test")
        try {
          val file         = Files.write(dir.resolve("file.bin"), Array.fill(2000)(7.toByte))
          val size         = Files.size(file)
          val modifiedTime = Files.getLastModifiedTime(file).toMillis
          val previous     = InMemoryFileStore(Seq(FileInfo(file, "cached-hash", size, modifiedTime, Some("(dev=9,ino=9)"))))

          var hashed                                             = false
          def countingHash(path: java.nio.file.Path): IO[String] = IO { hashed = true } >> hashFile(path)

          LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, countingHash, parallelism = 4).compile.drain
            .unsafeRunSync()

          hashed shouldBe true
        } finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }

      "take the shortcut when the stored entry has no fileKey" in {
        // legacy rows (fileKey = None) must not force a rehash; the key is backfilled via FileMetaChanged instead
        val dir = Files.createTempDirectory("scanner-no-key-test")
        try {
          val file         = Files.write(dir.resolve("file.bin"), Array.fill(2000)(7.toByte))
          val size         = Files.size(file)
          val modifiedTime = Files.getLastModifiedTime(file).toMillis
          val hash         = hashFile(file).unsafeRunSync()
          val previous     = InMemoryFileStore(Seq(FileInfo(file, hash, size, modifiedTime, None)))

          def explodingHash(path: java.nio.file.Path): IO[String] =
            IO.raiseError(new RuntimeException(s"hash must be skipped for unchanged file: $path"))

          val events =
            LocalDirectoryScanner.scanDirectory(dir, previous, _ => true, _ => true, explodingHash, parallelism = 4).compile.toList
              .unsafeRunSync()

          events should have size 1
          events.head.asInstanceOf[FileMetaChanged].fileInfo.fileKey shouldBe defined
        } finally Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }
    }
  }
}
