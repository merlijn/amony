package nl.amony.platform.concurrent

import java.util.concurrent.ConcurrentHashMap

import cats.effect.std.MapRef
import cats.effect.{Deferred, IO}
import cats.syntax.all.*

/**
 * Runs operations keyed by `K` at most once concurrently: callers with the same key while an operation is in flight wait
 * for its result. The in-flight entry is removed when the operation completes, so later calls run it again.
 */
final class SingleFlight[K, A] private (
  inFlight: MapRef[IO, K, Option[Deferred[IO, Either[Throwable, A]]]]
):

  def run(key: K)(operation: IO[A]): IO[A] =
    Deferred[IO, Either[Throwable, A]].flatMap { deferred =>
      inFlight(key).modify {
        case Some(existing) => (Some(existing), existing.get.rethrow)
        case None           =>
          val result = operation
            .attempt
            .flatTap(deferred.complete)
            .flatTap(_ => inFlight(key).set(None))
            .rethrow
          (Some(deferred), result)
      }
    }.flatten

object SingleFlight:
  def apply[K, A]: SingleFlight[K, A] =
    new SingleFlight[K, A](
      MapRef.fromConcurrentHashMap[IO, K, Deferred[IO, Either[Throwable, A]]](
        new ConcurrentHashMap[K, Deferred[IO, Either[Throwable, A]]]()
      )
    )
