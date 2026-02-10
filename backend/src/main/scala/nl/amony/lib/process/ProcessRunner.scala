package nl.amony.lib.process

import cats.effect.IO
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import scribe.Logging

trait ProcessRunner(val meter: Meter[IO]) extends Logging {

  def toString(is: fs2.Stream[IO, Byte]): IO[String] = is.compile.toVector.map(_.toArray).map(new String(_))

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

  private def failOnNonZeroExit(cmds: Seq[String])(process: fs2.io.process.Process[IO]): IO[Int] =
    process.exitValue.flatMap { exitCode =>
      if exitCode != 0 then {
        tail(process.stderr, 10).flatMap { errOutput =>
          val msg = s"""Non zero exit code for command: ${cmds.mkString(" ")} \nLast 10 lines of error output:\n$errOutput"""
          IO(logger.error(msg)) >> IO.raiseError(new IllegalStateException(msg))
        }
      } else
        IO.pure(exitCode)
    }

  private def getOutputAsString(process: fs2.io.process.Process[IO], useErrorStream: Boolean, cmds: Seq[String]): IO[String] =
    val is = if useErrorStream then process.stderr else process.stdout
    process.exitValue.flatMap { exitCode =>
      if exitCode != 0 then logger.warn(s"""Non zero exit code for command: ${cmds.mkString(" ")} \n""")
      toString(is)
    }

  def runIgnoreOutput(cmd: String, args: Seq[String]): IO[Int] =
    useProcess(cmd, args)(failOnNonZeroExit(cmd +: args))

  def useProcessOutput[T](cmd: String, args: Seq[String], useErrorStream: Boolean)(fn: String => IO[T]): IO[T] =
    useProcess(cmd, args)(process => getOutputAsString(process, useErrorStream, cmd +: args).flatMap(fn))

  def useProcess[T](cmd: String, args: Seq[String])(processHandler: fs2.io.process.Process[IO] => IO[T]): IO[T] = {
    fs2.io.process.ProcessBuilder(cmd, args.toList)
      .spawn[IO].use(processHandler)
      .timed.flatMap { case (duration, result) =>
        logger.debug(s"Process '$cmd ${args.mkString(" ")}' completed in ${duration.toMillis} ms")
        meter.counter[Long]("duration-ms").create.flatMap(_.inc()) >> IO.pure(result)
      }
  }
}
