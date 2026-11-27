package nl.amony.modules.resources.impl

import java.nio.file.Files
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessSettings}

class FileOperationsSpec extends AnyWordSpecLike with Matchers:

  given Meter[IO]  = Meter.noop[IO]
  given Tracer[IO] = Tracer.noop[IO]

  private def ops(dir: java.nio.file.Path): FileOperations =
    val config = LocalDirectoryConfig(
      id                    = s"bucket-${UUID.randomUUID()}",
      path                  = dir,
      sync                  = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 60.seconds,
        includePatterns = List("**/*"),
        excludePatterns = Nil
      ),
      hashingAlgorithm      = PartialHash,
      relativeUploadPath    = java.nio.file.Path.of("_upload"),
      generatePreviewsOnAdd = false
    )
    val store  = LocalDirectoryStore(config)
    FileOperations(
      config.hashingAlgorithm,
      1,
      ThumbnailFormats(List(ImageFormat.Webp)),
      ThumbnailResolutions(List(512), 512, 64),
      ProcessSettings(),
      ProcessLimiter.unlimited,
      store
    )

  private def resource(path: String): ResourceInfo =
    ResourceInfo(
      bucketId    = BucketId("bucket"),
      resourceId  = ResourceId(UUID.randomUUID().toString),
      userId      = UserId("admin"),
      path        = ResourcePath(path),
      partialHash = "test-hash",
      size        = 1L
    )

  "FileOperations.derivedResource" should {

    "return an error when the source file is gone" in {
      val operations = ops(Files.createTempDirectory("amony-ops-spec"))

      operations
        .derivedResource(resource("missing.mp4"), VideoFragment(Some(512), None, 0L, 1000L))
        .value
        .unsafeRunSync() should matchPattern { case Left(DerivedResourceError.SourceMissing(_, _)) => }
    }

    "return an error for an invalid path" in {
      val operations = ops(Files.createTempDirectory("amony-ops-spec"))

      operations
        .derivedResource(resource("/abs.mp4"), VideoFragment(Some(512), None, 0L, 1000L))
        .value
        .unsafeRunSync() should matchPattern { case Left(DerivedResourceError.InvalidPath(_, _)) => }
    }
  }
