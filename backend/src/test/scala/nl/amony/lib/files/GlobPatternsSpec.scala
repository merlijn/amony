package nl.amony.lib.files

import java.nio.file.Path
import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.resources.ResourceConfig.{LocalDirectoryConfig, PartialHash, ScanConfig}

class GlobPatternsSpec extends AnyWordSpecLike with Matchers {

  private val media = GlobPatterns.unsafeParse(List("**/*.{mp4,mkv,jpg}"))

  "GlobPatterns" should {
    "match files in the root and in sub directories" in {
      media.matches("a.mp4") shouldBe true
      media.matches("x/a.mkv") shouldBe true
      media.matches("x/y/a.jpg") shouldBe true
      media.matches("a.txt") shouldBe false
      media.matches("x/a.mp4/b.txt") shouldBe false
    }

    "match case-insensitively" in {
      media.matches("Holiday.MP4") shouldBe true
      GlobPatterns.unsafeParse(List("Movies/**")).matches("movies/a.mp4") shouldBe true
    }

    "anchor patterns at the root and ignore a leading slash" in {
      val anchored = GlobPatterns.unsafeParse(List("/samples/**"))
      anchored.matches("samples/a.mp4") shouldBe true
      anchored.matches("x/samples/a.mp4") shouldBe false
    }

    "match hidden files and directories" in {
      val hidden = GlobPatterns.unsafeParse(List("**/.*"))
      hidden.matches(".DS_Store") shouldBe true
      hidden.matches("x/.hidden") shouldBe true
      hidden.matches("x/visible.mp4") shouldBe false
    }

    "reject invalid and empty patterns" in {
      GlobPatterns.parse(List("**/*.{mp4")) shouldBe a[Left[?, ?]]
      GlobPatterns.parse(List(" ")) shouldBe a[Left[?, ?]]
      GlobPatterns.parse(List("**/*.mp4", "[a-")) shouldBe Left("Invalid pattern: '[a-'")
    }
  }

  "The local directory filters" should {
    val root   = Path.of("/media")
    val config = LocalDirectoryConfig(
      id                 = "media",
      path               = root,
      sync               = ScanConfig(
        enabled            = false,
        syncOnStartup      = false,
        newFilesOwner      = "admin",
        scanParallelFactor = 1,
        pollInterval       = 1.minute,
        includePatterns    = List("**/*.{mp4,jpg}"),
        excludePatterns    = List("**/.*", "**/@eaDir", "samples/**")
      ),
      hashingAlgorithm   = PartialHash,
      relativeUploadPath = Path.of("_upload")
    )

    "include files matching an include pattern and no exclude pattern" in {
      config.filterFiles(root.resolve("a.mp4")) shouldBe true
      config.filterFiles(root.resolve("x/A.JPG")) shouldBe true
      config.filterFiles(root.resolve("a.txt")) shouldBe false
      config.filterFiles(root.resolve(".a.mp4")) shouldBe false
      config.filterFiles(root.resolve("samples/a.mp4")) shouldBe false
    }

    "skip directories matching an exclude pattern" in {
      config.filterDirectory(root) shouldBe true
      config.filterDirectory(root.resolve("x")) shouldBe true
      config.filterDirectory(root.resolve("x/@eaDir")) shouldBe false
      config.filterDirectory(root.resolve(".hidden")) shouldBe false
    }

    "always skip the upload and .amony directories" in {
      val noExcludes = config.copy(sync = config.sync.copy(excludePatterns = Nil))
      noExcludes.filterDirectory(root.resolve("_upload")) shouldBe false
      noExcludes.filterDirectory(root.resolve(".amony")) shouldBe false
    }
  }
}
