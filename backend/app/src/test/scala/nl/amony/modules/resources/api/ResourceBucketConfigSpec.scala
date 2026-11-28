package nl.amony.modules.resources.api

import java.net.URI
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

class ResourceBucketConfigSpec extends AnyWordSpecLike with Matchers {

  private def localStorage(path: Path): LocalDirectoryStorageConfig =
    LocalDirectoryStorageConfig(
      path               = path,
      sync               = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 1.minute,
        includePatterns = List("**/*.{mp4,jpg}"),
        excludePatterns = List("**/.*", "**/@eaDir", "samples/**")
      ),
      relativeUploadPath = Path.of("_upload")
    )

  private def localConfig(path: Path): ResourceBucketConfig =
    ResourceBucketConfig(id = "media", storage = localStorage(path))

  private def s3Storage(
    bucket: String                           = "amony-media",
    region: String                           = "eu-west-1",
    endpoint: Option[URI]                    = None,
    prefix: Option[String]                   = None,
    pathStyleAccess: Boolean                 = false,
    presignedUrlTtl: FiniteDuration          = 15.minutes
  ): S3StorageConfig =
    S3StorageConfig(
      bucket          = bucket,
      region          = region,
      endpoint        = endpoint,
      prefix          = prefix,
      pathStyleAccess = pathStyleAccess,
      presignedUrlTtl = presignedUrlTtl
    )

  private def s3Config(storage: S3StorageConfig = s3Storage()): ResourceBucketConfig =
    ResourceBucketConfig(id = "s3-media", storage = storage)

  "LocalDirectoryStorageConfig" when {

    "filtering files and directories" should {
      val root   = Path.of("/media")
      val filter = localStorage(root)

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

    "validating the configuration" should {

      "return the config with an absolute, normalized path" in {
        val dir      = Files.createTempDirectory("amony-config-test")
        val relative = Path.of("").toAbsolutePath.relativize(dir.resolve("x/.."))

        localConfig(relative).validate().unsafeRunSync() shouldBe Right(localConfig(dir))
      }

      "reject invalid configurations" in {
        val dir = Files.createTempDirectory("amony-config-test")
        val s   = localStorage(dir)

        def error(invalid: LocalDirectoryStorageConfig): String =
          ResourceBucketConfig(id = "media", storage = invalid).validate().unsafeRunSync().left.toOption.get

        error(localStorage(dir.resolve("missing"))) should include("does not exist")
        error(s.copy(relativeUploadPath = dir.resolve("upload"))) shouldBe "Upload path must be relative to the bucket path"
        error(s.copy(relativeUploadPath = Path.of("../outside"))) shouldBe "Upload path must be a sub directory of the bucket path"
        error(s.copy(sync = s.sync.copy(includePatterns = Nil))) shouldBe "At least one include pattern is required"
        error(s.copy(sync = s.sync.copy(excludePatterns = List("{x")))) shouldBe "Invalid pattern: '{x'"
        error(s.copy(sync = s.sync.copy(pollInterval = 0.seconds))) shouldBe "Poll interval must be at least 1 second"
        error(s.copy(sync = s.sync.copy(newFilesOwner = " "))) shouldBe "New files owner is required"
      }
    }

    "detecting overlaps" should {

      "be detected when one path contains the other" in {
        localConfig(Path.of("/media")).overlaps(localConfig(Path.of("/media/sub"))) shouldBe true
        localConfig(Path.of("/media/sub")).overlaps(localConfig(Path.of("/media"))) shouldBe true
        localConfig(Path.of("/media")).overlaps(localConfig(Path.of("/media"))) shouldBe true
        localConfig(Path.of("/media")).overlaps(localConfig(Path.of("/media2"))) shouldBe false
        localConfig(Path.of("/movies")).overlaps(localConfig(Path.of("/media"))) shouldBe false
        localConfig(Path.of("/media")).overlaps(s3Config()) shouldBe false
      }
    }

    "updating the configuration" should {

      "allow updates within the same storage type and reject switching storage types" in {
        val c = localConfig(Path.of("/media"))
        c.checkUpdateAllowed(c.copy(generatePreviewsOnAdd = true)) shouldBe Right(())
        c.checkUpdateAllowed(s3Config()) shouldBe Left("The storage type of an existing bucket cannot be changed")
      }
    }
  }

  "S3StorageConfig" when {

    "validating the configuration" should {

      "normalize bucket, region and prefix" in {
        val raw = s3Config(s3Storage(bucket = " my-bucket ", region = " us-east-1 ", prefix = Some(" /media/photos/ ")))
        raw.validate().unsafeRunSync() shouldBe Right(
          s3Config(s3Storage(bucket = "my-bucket", region = "us-east-1", prefix = Some("media/photos")))
        )
      }

      "reject invalid configurations" in {
        def error(invalid: S3StorageConfig): String = s3Config(invalid).validate().unsafeRunSync().left.toOption.get

        error(s3Storage(bucket = "  ")) shouldBe "S3 bucket name is required"
        error(s3Storage(region = "")) shouldBe "S3 region is required"
        error(s3Storage(endpoint = Some(URI.create("ftp://example.com")))) should include("valid http or https URL")
        error(s3Storage(presignedUrlTtl = 0.seconds)) shouldBe "Presigned URL TTL must be at least 1 second"
        error(s3Storage(presignedUrlTtl = 8.days)) shouldBe "Presigned URL TTL must be at most 7 days"
      }
    }

    "detecting overlaps" should {

      "detect overlapping prefixes in the same bucket and ignore distinct buckets or sibling prefixes" in {
        s3Config(s3Storage(prefix = None)).overlaps(s3Config(s3Storage(prefix = Some("a")))) shouldBe true
        s3Config(s3Storage(prefix = Some("a"))).overlaps(s3Config(s3Storage(prefix = Some("a/b")))) shouldBe true
        s3Config(s3Storage(prefix = Some("a"))).overlaps(s3Config(s3Storage(prefix = Some("ab")))) shouldBe false
        s3Config(s3Storage(bucket = "b1")).overlaps(s3Config(s3Storage(bucket = "b2"))) shouldBe false
      }
    }

    "loading from HOCON" should {

      "parse both LocalDirectory and S3 storage configs" in {
        val s3Hocon =
          """
            |bucket {
            |  id = "cloud"
            |  generate-previews-on-add = true
            |  storage {
            |    type = "S3"
            |    bucket = "my-bucket"
            |    region = "eu-central-1"
            |    endpoint = "http://localhost:9000"
            |    prefix = "media"
            |    path-style-access = true
            |    presigned-url-ttl = 10 minutes
            |  }
            |}
            |""".stripMargin

        val parsed = ConfigSource.fromConfig(ConfigFactory.parseString(s3Hocon)).at("bucket").load[ResourceBucketConfig]
        parsed shouldBe Right(
          ResourceBucketConfig(
            id                    = "cloud",
            generatePreviewsOnAdd = true,
            storage               = S3StorageConfig(
              bucket          = "my-bucket",
              region          = "eu-central-1",
              endpoint        = Some(URI.create("http://localhost:9000")),
              prefix          = Some("media"),
              pathStyleAccess = true,
              presignedUrlTtl = 10.minutes
            )
          )
        )
      }

      "parse the bundled resources.conf default-bucket" in {
        val loaded = ConfigSource.resources("resources.conf").at("default-bucket").load[ResourceBucketConfig]
        loaded.isRight shouldBe true
        loaded.toOption.get.storage shouldBe a[LocalDirectoryStorageConfig]
      }
    }
  }

  "BucketRow" should {

    "decode legacy LocalDirectory rows that still contain hashingAlgorithm and round-trip S3 rows" in {
      import io.circe.parser.parse
      import nl.amony.modules.resources.dal.BucketRow

      val legacyJson = parse(
        """{
          |  "path": "/media",
          |  "relativeUploadPath": "_upload",
          |  "generatePreviewsOnAdd": false,
          |  "hashingAlgorithm": "partial-hash",
          |  "sync": {
          |    "enabled": false,
          |    "syncOnStartup": true,
          |    "newFilesOwner": "admin",
          |    "pollIntervalSeconds": 300,
          |    "includePatterns": ["**/*.mp4"],
          |    "excludePatterns": ["**/.*"]
          |  }
          |}""".stripMargin
      ).toOption.get

      val legacyRow = BucketRow("media", BucketRow.localDirectoryType, None, legacyJson)
      BucketRow.toConfig(legacyRow).map(_.storage) shouldBe a[Right[?, LocalDirectoryStorageConfig]]

      val s3Cfg = s3Config(
        s3Storage(
          bucket          = "my-bucket",
          region          = "eu-west-1",
          endpoint        = Some(URI.create("http://localhost:9000")),
          prefix          = Some("media"),
          pathStyleAccess = true,
          presignedUrlTtl = 10.minutes
        )
      )
      BucketRow.toConfig(BucketRow.fromConfig(s3Cfg)) shouldBe Right(s3Cfg)
    }
  }
}
