package nl.amony.lib.files

import java.nio.file.Files

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FileInfoSpec extends AnyWordSpec with Matchers {

  "FileInfo" should {
    "capture the fileKey when the filesystem provides one" in {
      val path = Files.createTempFile("file-info-test", ".bin")
      try {
        Files.write(path, Array.fill(10)(1.toByte))
        val info = FileInfo(path, partialHash = "hash")

        info.fileKey shouldBe defined
        info.fileKey shouldBe Option(Files.readAttributes(path, classOf[java.nio.file.attribute.BasicFileAttributes]).fileKey().toString)
      } finally Files.deleteIfExists(path)
    }

    "distinguish fileKeys in isSameFileMeta" in {
      val base = FileInfo(java.nio.file.Paths.get("a"), partialHash = "h", size = 100, modifiedTime = 1, fileKey = Some("(dev=1,ino=1)"))

      base.isSameFileMeta(base) shouldBe true
      base.isSameFileMeta(base.copy(fileKey = Some("(dev=1,ino=2)"))) shouldBe false
      base.isSameFileMeta(base.copy(fileKey = None)) shouldBe false
      base.isSameFileMeta(base.copy(size = 101)) shouldBe false
    }
  }
}
