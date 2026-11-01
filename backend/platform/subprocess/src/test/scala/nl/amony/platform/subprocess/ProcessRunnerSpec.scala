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

  private val noisyScript =
    """i=0
      |while [ "$i" -lt 20000 ]; do
      |  echo "stderr line $i" >&2
      |  echo "stdout line $i"
      |  i=$((i+1))
      |done
      |""".stripMargin

  private val noisyStderrScript =
    """i=0
      |while [ "$i" -lt 20000 ]; do
      |  echo "stderr line $i" >&2
      |  i=$((i+1))
      |done
      |echo done
      |""".stripMargin

  "ProcessRunner" should {

    "time out a long-running process" in {
      val result = runner(ProcessSettings())
        .runIgnoreOutput("sleep", Command("sleep", List("5")), 100.millis)
        .attempt
        .unsafeRunSync()

      assert(result.left.exists(_.isInstanceOf[ProcessTimedOut]))
    }

    "drain both output streams while waiting for the process to exit" in {
      val result = runner(ProcessSettings())
        .runIgnoreOutput("noisy", Command("sh", List("-c", noisyScript)), 5.seconds)
        .unsafeRunSync()

      assert(result == 0)
    }

    "drain the unused stream while collecting the selected output" in {
      val result = runner(ProcessSettings())
        .useProcessOutput("noisy-stderr", Command("sh", List("-c", noisyStderrScript)), useErrorStream = false, 5.seconds)(out => IO.pure(out.trim))
        .unsafeRunSync()

      assert(result == "done")
    }
  }
}
