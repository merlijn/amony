package nl.amony.platform.subprocess

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

class ProcessRunnerSpec extends AnyWordSpecLike {

  private def runner(processSettings: ProcessSettings): ProcessRunner =
    new ProcessRunner(using Meter.noop[IO], Tracer.noop[IO]) {
      override protected val settings: ProcessSettings = processSettings
      override protected val limiter: ProcessLimiter   = ProcessLimiter.unlimited
    }

  "ProcessRunner" should {

    "time out a long-running process" in {
      val result = runner(ProcessSettings())
        .runIgnoreOutput("sleep", Command("sleep", List("5")), 100.millis)
        .attempt
        .unsafeRunSync()

      assert(result.left.exists(_.isInstanceOf[ProcessTimedOut]))
    }
  }
}
