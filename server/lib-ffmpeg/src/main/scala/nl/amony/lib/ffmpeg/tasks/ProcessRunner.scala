package nl.amony.lib.ffmpeg.tasks

import cats.effect.IO
import scribe.Logging

trait ProcessRunner extends Logging {

  def exec(cmds: Seq[String]): Process = {
    logger.debug(s"Running command: ${cmds.mkString(" ")}")
    Runtime.getRuntime.exec(cmds.toArray)
  }

  def unsafeExecSync(useErrorStream: Boolean, cmds: Seq[String]): String = {
    val process  = exec(cmds)
    getOutputAsString(process, useErrorStream, cmds)
  }

  private def getOutputAsString(process: Process, useErrorStream: Boolean, cmds: Seq[String]): String = {
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(" ")} \n""" + output)

    output
  }

  def runIgnoreOutput(cmds: Seq[String], useErrorStream: Boolean): IO[Unit] = runWithOutput(cmds, useErrorStream){ _ => IO.unit }

  def runWithOutput[T](cmds: Seq[String], useErrorStream: Boolean)(fn: String => IO[T]): IO[T] = {
    runCmd(cmds) { process =>

      val output = getOutputAsString(process, useErrorStream, cmds)

      fn(output)
    }
  }

  def runCmd[T](cmds: Seq[String])(fn: Process => IO[T]): IO[T] = {

    IO { exec(cmds) }
      .flatMap { process => fn(process).onCancel( IO { process.destroy() })
    }
  }
}
