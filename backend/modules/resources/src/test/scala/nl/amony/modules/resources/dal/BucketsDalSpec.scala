package nl.amony.modules.resources.dal

import java.net.URI
import scala.concurrent.duration.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.resources.api.{LocalDirectoryStorageConfig, ResourceBucketConfig, S3StorageConfig}

class BucketsDalSpec extends AnyWordSpecLike with Matchers {

  private def s3Config(storage: S3StorageConfig): ResourceBucketConfig =
    ResourceBucketConfig(id = "s3-media", storage = storage)

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

  "BucketRow" when {

    "decoding stored rows" should {

      "decode legacy LocalDirectory rows that still contain hashingAlgorithm and round-trip S3 rows" in {
        val legacyJson = io.circe.parser
          .parse(
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
          )
          .toOption
          .get

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
}
