package nl.amony.lib.process

import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter
import scribe.Logging

case class Command(cmd: String, args: Seq[String]) {
  override def toString: String = s"$cmd ${args.mkString(" ")}"
}

trait ProcessRunner(using meter: Meter[IO]) extends Logging {

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
    process.exitValue.flatMap { exitCode =>
      if exitCode != 0 then {
        tail(process.stderr, 10).flatMap { errOutput =>
          val msg = s"""Non zero exit code for command: $command \nLast 10 lines of error output:\n$errOutput"""
          IO(logger.error(msg)) >> IO.raiseError(new IllegalStateException(msg))
        }
      } else
        IO.pure(exitCode)
    }

  private def getOutputAsString(process: fs2.io.process.Process[IO], useErrorStream: Boolean, command: Command): IO[String] =
    val is = if useErrorStream then process.stderr else process.stdout
    process.exitValue.flatMap { exitCode =>
      if exitCode != 0 then logger.warn(s"""Non zero exit code for command: $command\n""")
      compileToString(is)
    }

  def runIgnoreOutput(name: String, command: Command): IO[Int] =
    useProcess(name, command)(failOnNonZeroExit(command))

  def useProcessOutput[T](name: String, command: Command, useErrorStream: Boolean)(fn: String => IO[T]): IO[T] =
    useProcess(name, command)(process => getOutputAsString(process, useErrorStream, command).flatMap(fn))

  def useProcess[T](name: String, cmd: Command)(processHandler: fs2.io.process.Process[IO] => IO[T]): IO[T] =
    fs2.io.process.ProcessBuilder(cmd.cmd, cmd.args.toList)
      .spawn[IO].use(processHandler)
      .timed.flatMap { case (duration, result) =>
        logger.debug(s"Process '$cmd ${cmd.args.mkString(" ")}' completed in ${duration.toMillis} ms")
        meter.histogram[Long](cmd.cmd).create.flatMap(_.record(duration.toMillis)) >> IO.pure(result)
      }
}
