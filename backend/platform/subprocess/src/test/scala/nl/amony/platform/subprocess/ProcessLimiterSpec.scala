package nl.amony.platform.subprocess

import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import org.scalatest.wordspec.AnyWordSpecLike

class ProcessLimiterSpec extends AnyWordSpecLike {

  private def awaitWaiting(limiter: ProcessLimiter, count: Int): IO[Unit] =
    limiter.waitingCount.flatMap { current =>
      if current >= count then IO.unit
      else IO.sleep(10.millis) *> awaitWaiting(limiter, count)
    }.timeout(2.seconds)

  "ProcessLimiter" should {

    "limit the number of concurrent permits" in {
      val settings = ProcessSettings(maxConcurrent = 2, maxQueued = 10, queueTimeout = 5.seconds)

      ProcessLimiter.resource(settings).use { limiter =>
        for
          active    <- Ref.of[IO, Int](0)
          maxActive <- Ref.of[IO, Int](0)
          work       = limiter.use {
                         active.updateAndGet(_ + 1).flatTap(n => maxActive.update(_ max n)) >>
                           IO.sleep(50.millis) >>
                           active.update(_ - 1)
                       }
          fibers    <- List.fill(4)(work.start).sequence
          _         <- fibers.traverse(_.join)
          peak      <- maxActive.get
        yield assert(peak == 2)
      }.unsafeRunSync()
    }

    "reject work when the bounded queue is full" in {
      val settings = ProcessSettings(maxConcurrent = 1, maxQueued = 1, queueTimeout = 5.seconds)

      ProcessLimiter.resource(settings).use { limiter =>
        for
          started <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          holder  <- limiter.use(started.complete(()) >> release.get).start
          _       <- started.get
          waiter1 <- limiter.use(IO.unit).start
          _       <- awaitWaiting(limiter, 1)
          waiter2 <- limiter.use(IO.unit).start
          result2 <- waiter2.joinWithNever.attempt
          _       <- release.complete(())
          result1 <- waiter1.joinWithNever.attempt
          _       <- holder.join
        yield {
          assert(result1.isRight)
          assert(result2.left.exists(_.isInstanceOf[ProcessBusy]))
        }
      }.unsafeRunSync()
    }

    "time out while waiting for a permit" in {
      val settings = ProcessSettings(maxConcurrent = 1, maxQueued = 2, queueTimeout = 100.millis)

      ProcessLimiter.resource(settings).use { limiter =>
        for
          started <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          holder  <- limiter.use(started.complete(()) >> release.get).start
          _       <- started.get
          result  <- limiter.use(IO.unit).attempt
          _       <- release.complete(())
          _       <- holder.join
        yield assert(result.left.exists(_.isInstanceOf[ProcessBusy]))
      }.unsafeRunSync()
    }
  }
}
