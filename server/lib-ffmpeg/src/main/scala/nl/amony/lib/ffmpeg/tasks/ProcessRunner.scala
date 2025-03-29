package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import org.slf4j.LoggerFactory

trait ProcessRunner {
  
  val logger = LoggerFactory.getLogger(getClass)
  
  def toString(is: fs2.Stream[IO, Byte]): IO[String] = is.compile.toVector.map(_.toArray).map(new String(_))

  private def getOutputAsString(process: fs2.io.process.Process[IO], useErrorStream: Boolean, cmds: Seq[String]): IO[String] = {
    val is       = if (useErrorStream) process.stderr else process.stdout

    process.exitValue.flatMap { exitCode =>
      if (exitCode != 0)
        logger.warn(s"""Non zero exit code for command: ${cmds.mkString(" ")} \n""")
      
      toString(is)
    }
  }

  def runIgnoreOutput(cmd: String, args: Seq[String]): IO[Int] = useProcess(cmd, args)(_.exitValue)

  def useProcessOutput[T](cmd: String, args: Seq[String], useErrorStream: Boolean)(fn: String => IO[T]): IO[T] = 
    useProcess(cmd, args) { process => getOutputAsString(process, useErrorStream, cmd +: args).flatMap(fn) }

  def useProcess[T](cmd: String, args: Seq[String])(fn: fs2.io.process.Process[IO] => IO[T]): IO[T] =
    fs2.io.process.ProcessBuilder(cmd, args.toList).spawn[IO].use { process => fn(process) }
}
