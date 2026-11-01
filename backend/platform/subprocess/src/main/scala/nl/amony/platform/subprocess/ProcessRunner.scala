package nl.amony.platform.subprocess

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.platform.subprocess.ffmpeg.HardwareDecoding

case class Command(cmd: String, args: Seq[String]) {
  override def toString: String = s"$cmd ${args.mkString(" ")}"
}

final case class ProcessTimedOut(command: Command, timeout: FiniteDuration)
    extends Exception(s"Process timed out after $timeout: $command")

trait ProcessRunner(using meter: Meter[IO], tracer: Tracer[IO]) extends Logging {

  protected def settings: ProcessSettings
  protected def limiter: ProcessLimiter

  /** Hardware decoding for ffmpeg, resolved once from the configured mode and the available render node. */
  protected lazy val hardwareDecoding: Option[HardwareDecoding] = {
    val resolved = HardwareDecoding.resolve(settings.hardwareDecode, settings.vaapiDevice)
    resolved match
      case Some(_) => logger.info(s"Decoding video with VAAPI on ${settings.vaapiDevice}")
      case None    =>
        if settings.hardwareDecode != HardwareDecode.Disabled then
          logger.info(s"No readable VAAPI device at ${settings.vaapiDevice}, decoding video in software")
    resolved
  }

  def compileToString(is: fs2.Stream[IO, Byte]): IO[String] = is.compile.toVector.map(_.toArray).map(new String(_))

  def tail(is: fs2.Stream[IO, Byte], nrOfLines: Int): IO[String] =
    is.through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .fold(Vector.empty[String]) { (acc, line) =>
        if acc.size >= nrOfLines then acc.drop(1) :+ line
        else acc :+ line
      }
      .map(_.mkString("\n"))
      .compile
      .last
      .map(_.getOrElse(""))

  private def failOnNonZeroExit(command: Command)(process: fs2.io.process.Process[IO]): IO[Int] =
    (process.exitValue, process.stdout.compile.drain, tail(process.stderr, 10)).parTupled.flatMap {
      case (exitCode, _, errOutput) =>
        if exitCode != 0 then {
          val msg = s"""Non zero exit code for command: $command \nLast 10 lines of error output:\n$errOutput"""
          IO(logger.error(msg)) >> IO.raiseError(new IllegalStateException(msg))
        } else
          IO.pure(exitCode)
    }

  private def getOutputAsString(process: fs2.io.process.Process[IO], useErrorStream: Boolean, command: Command): IO[String] = {
    val selected = if useErrorStream then process.stderr else process.stdout
    val unused   = if useErrorStream then process.stdout else process.stderr

    (process.exitValue, compileToString(selected), unused.compile.drain).parTupled.flatMap {
      case (exitCode, output, _) =>
        if exitCode != 0 then logger.warn(s"""Non zero exit code for command: $command\n""")
        IO.pure(output)
    }
  }

  def runIgnoreOutput(name: String, command: Command, timeout: FiniteDuration): IO[Int] =
    useProcess(name, command, timeout)(failOnNonZeroExit(command))

  def useProcessOutput[T](name: String, command: Command, useErrorStream: Boolean, timeout: FiniteDuration)(fn: String => IO[T]): IO[T] =
    useProcess(name, command, timeout)(process => getOutputAsString(process, useErrorStream, command).flatMap(fn))

  def useProcess[T](name: String, cmd: Command, timeout: FiniteDuration)(processHandler: fs2.io.process.Process[IO] => IO[T]): IO[T] =
    limiter.use {
      tracer.span(s"process.$name").use { _ =>
        fs2.io.process.ProcessBuilder(cmd.cmd, cmd.args.toList)
          .spawn[IO]
          .use(processHandler)
          .timeoutTo(timeout, IO.raiseError(ProcessTimedOut(cmd, timeout)))
          .timed
          .flatMap { case (duration, result) =>
            logger.debug(s"Process '$cmd' completed in ${duration.toMillis} ms")
            meter.histogram[Long](s"process.$name").create.flatMap(_.record(duration.toMillis)) >> IO.pure(result)
          }
      }
    }

  /** Runs a process whose stdout is consumed as a stream, holding the slot until the stream ends. */
  def streamProcess(name: String, cmd: Command, timeout: FiniteDuration)(handler: fs2.io.process.Process[IO] => Stream[IO, Byte]): Stream[IO, Byte] =
    Stream.resource(limiter.permit).flatMap { _ =>
      Stream.resource(fs2.io.process.ProcessBuilder(cmd.cmd, cmd.args.toList).spawn[IO]).flatMap { process =>
        handler(process)
          .timeout(timeout)
          .handleErrorWith {
            case _: TimeoutException => Stream.raiseError[IO](ProcessTimedOut(cmd, timeout))
            case error               => Stream.raiseError[IO](error)
          }
      }
    }
}
