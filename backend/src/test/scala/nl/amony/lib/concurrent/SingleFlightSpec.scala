package nl.amony.lib.concurrent

import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SingleFlightSpec extends AnyWordSpecLike with Matchers {

  "SingleFlight" when {

    "running an operation" should {

      "execute it only once for concurrent calls with the same key" in {
        val result =
          for
            executions   <- Ref.of[IO, Int](0)
            started      <- Deferred[IO, Unit]
            release      <- Deferred[IO, Unit]
            flight        = SingleFlight[String, String]
            operation     = executions.update(_ + 1) >> started.complete(()) >> release.get >> IO.pure("done")
            first        <- flight.run("key")(operation).start
            second       <- flight.run("key")(operation).start
            _            <- started.get
            _            <- IO.sleep(50.millis)
            whileRunning <- executions.get
            _            <- release.complete(())
            a            <- first.joinWithNever
            b            <- second.joinWithNever
            total        <- executions.get
          yield
            whileRunning shouldBe 1
            a shouldBe "done"
            b shouldBe "done"
            total shouldBe 1
        result.unsafeRunSync()
      }

      "execute operations for different keys independently" in {
        val result =
          for
            executions <- Ref.of[IO, Int](0)
            flight      = SingleFlight[String, String]
            operation   = executions.update(_ + 1) >> IO.sleep(50.millis).as("done")
            results    <- (flight.run("a")(operation), flight.run("b")(operation)).parTupled
            total      <- executions.get
          yield
            results shouldBe (("done", "done"))
            total shouldBe 2
        result.unsafeRunSync()
      }

      "run the operation again after it completed" in {
        val result =
          for
            executions <- Ref.of[IO, Int](0)
            flight      = SingleFlight[String, String]
            operation   = executions.update(_ + 1).as("done")
            _          <- flight.run("key")(operation)
            _          <- flight.run("key")(operation)
            total      <- executions.get
          yield total shouldBe 2
        result.unsafeRunSync()
      }

      "clear the key after a failure so a retry runs it again" in {
        val error  = new RuntimeException("boom")
        val result =
          for
            executions <- Ref.of[IO, Int](0)
            flight      = SingleFlight[String, String]
            failed     <- flight.run("key")(IO.raiseError[String](error)).attempt
            retried    <- flight.run("key")(executions.update(_ + 1).as("recovered"))
            total      <- executions.get
          yield
            failed shouldBe Left(error)
            retried shouldBe "recovered"
            total shouldBe 1
        result.unsafeRunSync()
      }
    }
  }
}
