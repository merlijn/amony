package nl.amony.modules.resources.api

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LocalDirectoryConfigSpec extends AnyWordSpecLike with Matchers {

  private def config(path: Path): LocalDirectoryConfig =
    LocalDirectoryConfig(
      id                 = "media",
      path               = path,
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

  "The local directory filters" should {
    val root   = Path.of("/media")
    val filter = config(root)

    "include files matching an include pattern and no exclude pattern" in {
      filter.filterFiles(root.resolve("a.mp4")) shouldBe true
      filter.filterFiles(root.resolve("x/A.JPG")) shouldBe true
      filter.filterFiles(root.resolve("a.txt")) shouldBe false
      filter.filterFiles(root.resolve(".a.mp4")) shouldBe false
      filter.filterFiles(root.resolve("samples/a.mp4")) shouldBe false
    }

    "skip directories matching an exclude pattern" in {
      filter.filterDirectory(root) shouldBe true
      filter.filterDirectory(root.resolve("x")) shouldBe true
      filter.filterDirectory(root.resolve("x/@eaDir")) shouldBe false
      filter.filterDirectory(root.resolve(".hidden")) shouldBe false
    }

    "always skip the upload and .amony directories" in {
      val noExcludes = filter.copy(sync = filter.sync.copy(excludePatterns = Nil))
      noExcludes.filterDirectory(root.resolve("_upload")) shouldBe false
      noExcludes.filterDirectory(root.resolve(".amony")) shouldBe false
    }
  }

  "Validating a local directory config" should {

    "return the config with an absolute, normalized path" in {
      val dir      = Files.createTempDirectory("amony-config-test")
      val relative = Path.of("").toAbsolutePath.relativize(dir.resolve("x/.."))

      config(relative).validate().unsafeRunSync() shouldBe Right(config(dir))
    }

    "reject invalid configurations" in {
      val dir = Files.createTempDirectory("amony-config-test")
      val c   = config(dir)

      def error(invalid: LocalDirectoryConfig): String = invalid.validate().unsafeRunSync().left.toOption.get

      error(config(dir.resolve("missing"))) should include("does not exist")
      error(c.copy(relativeUploadPath = dir.resolve("upload"))) shouldBe "Upload path must be relative to the bucket path"
      error(c.copy(sync = c.sync.copy(includePatterns = Nil))) shouldBe "At least one include pattern is required"
      error(c.copy(sync = c.sync.copy(excludePatterns = List("{x")))) shouldBe "Invalid pattern: '{x'"
      error(c.copy(sync = c.sync.copy(pollInterval = 0.seconds))) shouldBe "Poll interval must be at least 1 second"
      error(c.copy(sync = c.sync.copy(scanParallelFactor = 0))) shouldBe "Scan parallel factor must be at least 1"
      error(c.copy(sync = c.sync.copy(newFilesOwner = " "))) shouldBe "New files owner is required"
    }
  }

  "Overlapping local directories" should {
    "be detected when one path contains the other" in {
      config(Path.of("/media")).overlaps(config(Path.of("/media/sub"))) shouldBe true
      config(Path.of("/media/sub")).overlaps(config(Path.of("/media"))) shouldBe true
      config(Path.of("/media")).overlaps(config(Path.of("/media"))) shouldBe true
      config(Path.of("/media")).overlaps(config(Path.of("/media2"))) shouldBe false
      config(Path.of("/movies")).overlaps(config(Path.of("/media"))) shouldBe false
    }
  }

  "Updating a local directory config" should {
    "allow changes that keep the hashing algorithm" in {
      val c = config(Path.of("/media"))
      c.checkUpdateAllowed(c.copy(generatePreviewsOnAdd = true)) shouldBe Right(())
    }
  }
}
