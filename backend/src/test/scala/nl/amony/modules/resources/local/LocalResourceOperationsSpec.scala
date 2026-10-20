package nl.amony.modules.resources.local

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Using

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.*
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import nl.amony.lib.messagebus.MessageTopic
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*

class LocalResourceOperationsSpec extends AnyWordSpecLike with Matchers {

  private object NoOpTopic extends MessageTopic[ResourceEvent]:

    override def publish(message: ResourceEvent): IO[Unit] = IO.unit

    override def publish(session: Session[IO], message: ResourceEvent): IO[Unit] = IO.unit

    override def processAtLeastOnce(consumerId: String)(processor: ResourceEvent => IO[Unit]): Stream[IO, Unit] = Stream.empty

  private class TestBucket(config: LocalDirectoryConfig, executions: AtomicInteger)
      extends LocalDirectoryBucket(
        config,
        1,
        null,
        NoOpTopic,
        ThumbnailFormats(List(ImageFormat.Webp)),
        ThumbnailResolutions(List(128), 128, stepDown = 0)
      )(using Meter.noop[IO], Tracer.noop[IO]) {

    override protected def run(info: ResourceInfo, inputFile: Path, outputFile: Path, operation: ResourceOperation): IO[Path] =
      IO.sleep(100.millis) >> IO.blocking {
        executions.incrementAndGet()
        Files.createDirectories(outputFile.getParent)
        Files.writeString(outputFile, "preview")
        outputFile
      }
  }

  private def config(path: Path): LocalDirectoryConfig =
    LocalDirectoryConfig(
      id                 = "media",
      path               = path,
      sync               = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 1.minute,
        includePatterns = List("**/*"),
        excludePatterns = Nil
      ),
      hashingAlgorithm   = PartialHash,
      relativeUploadPath = Path.of("_upload")
    )

  private def resourceInfo(path: Path): ResourceInfo =
    ResourceInfo(
      bucketId         = BucketId("media"),
      resourceId       = ResourceId(UUID.randomUUID().toString),
      userId           = UserId("admin"),
      path             = path.toString,
      size             = 1L,
      partialHash      = Some("hash"),
      fileKey          = Some("key"),
      timeLastModified = Some(1L)
    )

  private val operation = ImageThumbnail(width = Some(128), format = ImageFormat.Webp)

  private def withBucket[A](executions: AtomicInteger)(test: TestBucket => IO[A]): A = {
    val dir = Files.createTempDirectory("local-resource-operations")
    try test(new TestBucket(config(dir), executions)).unsafeRunSync()
    finally deleteRecursively(dir)
  }

  private def deleteRecursively(path: Path): Unit =
    Using.resource(Files.walk(path)) { stream =>
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(file => Files.delete(file))
    }

  "LocalResourceOperations" when {

    "derivedResource" should {

      "execute the operation only once when called concurrently" in {
        val executions = new AtomicInteger(0)

        withBucket(executions) { bucket =>
          val info = resourceInfo(Path.of("input.png"))

          (1 to 10).toList.parTraverse(_ => bucket.derivedResource(info, operation)).map { results =>
            executions.get() shouldBe 1
            results.flatten should have size 10
          }
        }
      }

      "execute separate operations for different keys" in {
        val executions = new AtomicInteger(0)

        withBucket(executions) { bucket =>
          val resources = List("a.png", "b.png", "c.png").map(path => resourceInfo(Path.of(path)))

          resources.parTraverse(info => bucket.derivedResource(info, operation)).map { results =>
            executions.get() shouldBe 3
            results.flatten should have size 3
          }
        }
      }
    }
  }
}
