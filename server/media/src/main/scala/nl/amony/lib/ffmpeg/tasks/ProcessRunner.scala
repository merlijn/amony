package nl.amony.lib.ffmpeg.tasks

import monix.eval.Task
import scribe.Logging

trait ProcessRunner extends Logging {

  def runSync(useErrorStream: Boolean, cmds: Seq[String]): String = {

    logger.debug(s"Running command: ${cmds.mkString(",")}")

    val process  = Runtime.getRuntime.exec(cmds.toArray)
    processOutput(process, useErrorStream, cmds)
  }

  private def processOutput(process: Process, useErrorStream: Boolean, cmds: Seq[String]) = {
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }

  def runUnsafe(cmds: Seq[String]): Process = {
    logger.debug(s"Running command: ${cmds.mkString(",")}")
    Runtime.getRuntime.exec(cmds.toArray)
  }

  def runWithOutput[T](cmds: Seq[String], useErrorStream: Boolean)(fn: String => Task[T]): Task[T] = {
    runCmd(cmds) { process =>

      val output = processOutput(process, useErrorStream, cmds)

      fn(output)
    }
  }

  def runCmd[T](cmds: Seq[String])(fn: Process => Task[T]): Task[T] = {

    Task { runUnsafe(cmds) }
      .flatMap { process => fn(process).doOnCancel( Task { process.destroy() })
    }
  }
}
