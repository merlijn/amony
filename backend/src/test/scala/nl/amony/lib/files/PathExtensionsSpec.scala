package nl.amony.lib.files

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PathExtensionsSpec extends AnyWordSpec with Matchers {

  "PathExtensions" when {

    "stripExtension" should {

      "remove the extension in the same directory" in {
        Paths.get("/media/_upload/video-faststart.mkv").stripExtension() shouldBe Paths.get("/media/_upload/video-faststart")
      }

      "keep a file without extension as is" in {
        Paths.get("/media/video").stripExtension() shouldBe Paths.get("/media/video")
      }

      "strip only the last extension" in {
        Paths.get("/media/archive.tar.gz").stripExtension() shouldBe Paths.get("/media/archive.tar")
      }

      "work for a relative file name without parent" in {
        Paths.get("video.mkv").stripExtension() shouldBe Paths.get("video")
      }
    }

    "fileExtension" should {

      "return the extension" in {
        Paths.get("/media/video.mkv").fileExtension() shouldBe Some("mkv")
      }

      "return None when there is no extension" in {
        Paths.get("/media/video").fileExtension() shouldBe None
      }
    }

    "fileNameWithoutExtension" should {

      "return the file name without extension" in {
        Paths.get("/media/_upload/video-faststart.mkv").fileNameWithoutExtension() shouldBe "video-faststart"
      }
    }

    "absoluteFileName" should {

      "return the normalized absolute path" in {
        Paths.get("/media/./video.mkv").absoluteFileName() shouldBe Paths.get("/media/video.mkv").toString
      }
    }

    "file attributes" should {

      "report size, timestamps and existence" in {
        val path = Files.createTempFile("path-extensions-test", ".bin")
        try {
          Files.write(path, Array.fill(10)(1.toByte))
          path.exists() shouldBe true
          path.size() shouldBe 10
          path.lastModifiedMillis() should be > 0L
          path.creationTimeMillis() should be >= 0L
          path.basicFileAttributes().isRegularFile shouldBe true
        } finally Files.deleteIfExists(path)
      }

      "report a missing file as not existing" in {
        Paths.get("/media/definitely-missing-file.bin").exists() shouldBe false
      }

      "delete an existing file and tolerate a missing one" in {
        val path = Files.createTempFile("path-extensions-test", ".bin")
        path.deleteIfExists()
        Files.exists(path) shouldBe false
        Paths.get("/media/definitely-missing-file.bin").deleteIfExists()
      }
    }

    "contentAsString" should {

      "read the file content" in {
        val path = Files.createTempFile("path-extensions-test", ".txt")
        try {
          Files.writeString(path, "hello", StandardCharsets.UTF_8)
          path.contentAsString(StandardCharsets.UTF_8) shouldBe "hello"
        } finally Files.deleteIfExists(path)
      }
    }

    "/" should {

      "resolve string and path children" in {
        val parent = Paths.get("/media")
        parent / "video.mkv" shouldBe Paths.get("/media/video.mkv")
        parent / Paths.get("video.mkv") shouldBe Paths.get("/media/video.mkv")
      }
    }
  }
}
