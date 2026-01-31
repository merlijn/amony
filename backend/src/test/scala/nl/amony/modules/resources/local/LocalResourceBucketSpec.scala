package nl.amony.modules.resources.local

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import cats.effect.std.MapRef
import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import cats.implicits.catsSyntaxParallelTraverse1
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LocalResourceBucketSpec extends AnyWordSpecLike with Matchers {

  "derivedResource" should {

    "execute the operation only once when called concurrently" in {
      val executionCount = new AtomicInteger(0)
      val tempDir        = Files.createTempDirectory("test-cache")

      val mapRef = MapRef.fromConcurrentHashMap[IO, String, Deferred[IO, Either[Throwable, Path]]](
        new ConcurrentHashMap()
      )

      def simulatedOperation(key: String): IO[Path] = {
        IO.sleep(100.millis) >> IO {
          executionCount.incrementAndGet()
          tempDir.resolve(s"$key.txt")
        }
      }

      def getOrCreateResource(key: String): IO[Path] = {
        Deferred[IO, Either[Throwable, Path]].flatMap {
          newDeferred =>
            mapRef(key).modify {
              case Some(existing) =>
                (Some(existing), existing.get.rethrow)
              case None           =>
                val runOp = simulatedOperation(key)
                  .attempt
                  .flatTap(result => newDeferred.complete(result))
                  .flatTap(_ => mapRef(key).set(None))
                  .rethrow
                (Some(newDeferred), runOp)
            }
        }.flatten
      }

      val result = (1 to 10)
        .toList
        .parTraverse(_ => getOrCreateResource("test-key"))
        .unsafeRunSync()

      executionCount.get() shouldBe 1
      result.distinct.size shouldBe 1

      // Cleanup
      Files.deleteIfExists(tempDir)
    }

    "execute separate operations for different keys" in {
      val executionCount = new AtomicInteger(0)
      val tempDir        = Files.createTempDirectory("test-cache")

      val mapRef = MapRef.fromConcurrentHashMap[IO, String, Deferred[IO, Either[Throwable, Path]]](
        new ConcurrentHashMap()
      )

      def simulatedOperation(key: String): IO[Path] = {
        IO.sleep(50.millis) >> IO {
          executionCount.incrementAndGet()
          tempDir.resolve(s"$key.txt")
        }
      }

      def getOrCreateResource(key: String): IO[Path] = {
        Deferred[IO, Either[Throwable, Path]].flatMap {
          newDeferred =>
            mapRef(key).modify {
              case Some(existing) =>
                (Some(existing), existing.get.rethrow)
              case None           =>
                val runOp = simulatedOperation(key)
                  .attempt
                  .flatTap(result => newDeferred.complete(result))
                  .flatTap(_ => mapRef(key).set(None))
                  .rethrow
                (Some(newDeferred), runOp)
            }
        }.flatten
      }

      val keys   = List("key-a", "key-b", "key-c")
      val result = keys
        .parTraverse(key => getOrCreateResource(key))
        .unsafeRunSync()

      executionCount.get() shouldBe 3
      result.size shouldBe 3

      Files.deleteIfExists(tempDir)
    }
  }
}
