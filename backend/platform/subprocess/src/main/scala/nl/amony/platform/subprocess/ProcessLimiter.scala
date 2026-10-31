package nl.amony.platform.subprocess

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref, Resource}

final case class ProcessBusy(maxConcurrent: Int, maxQueued: Int)
    extends Exception(s"All $maxConcurrent process slots are busy and the queue is full ($maxQueued waiting)")

/**
 * Limits the number of concurrently running external processes and rejects work once the bounded
 * waiting queue is exhausted, so overload turns into fast failures instead of unbounded fibers.
 */
trait ProcessLimiter:
  def permit: Resource[IO, Unit]
  def waitingCount: IO[Int]

  final def use[A](fa: IO[A]): IO[A] =
    permit.use(_ => fa)

object ProcessLimiter:

  val unlimited: ProcessLimiter = new ProcessLimiter:
    override def permit: Resource[IO, Unit] = Resource.unit
    override def waitingCount: IO[Int]      = IO.pure(0)

  def resource(settings: ProcessSettings): Resource[IO, ProcessLimiter] =
    for
      semaphore <- Resource.eval(Semaphore[IO](settings.maxConcurrent))
      waiting   <- Resource.eval(Ref.of[IO, Int](0))
    yield new ProcessLimiter:
      override def waitingCount: IO[Int] = waiting.get

      override def permit: Resource[IO, Unit] =
        Resource.make {
          waiting.modify { current =>
            if current >= settings.maxQueued then (current, false)
            else (current + 1, true)
          }.flatMap {
            case false => IO.raiseError(ProcessBusy(settings.maxConcurrent, settings.maxQueued))
            case true  =>
              semaphore.acquire
                .timeoutTo(settings.queueTimeout, IO.raiseError(ProcessBusy(settings.maxConcurrent, settings.maxQueued)))
                .guarantee(waiting.update(_ - 1))
          }
        }(_ => semaphore.release)
