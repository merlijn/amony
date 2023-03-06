package nl.amony.lib

import _root_.cats.effect.IO

import scala.concurrent.Future

package object cats {
  implicit class FutureOps[T](future: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(future))
  }
}
