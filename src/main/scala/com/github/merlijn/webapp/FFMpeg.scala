package com.github.merlijn.webapp

import better.files._

import java.time.Duration
import java.util.Base64
import scala.util.Random

object FFMpeg extends Logging {

  case class Probe(
    id: String,
    fileName: String,
    duration: Long,
    resolution: (Int, Int)
  )

  // samples a file randomly and creates a hash from that
  def fakeHash(file: File): String = {

    def md5hash(data: Array[Byte]): String = {
      import java.math.BigInteger
      import java.security.MessageDigest

      val sha256Digest: MessageDigest = MessageDigest.getInstance("MD5")
      val digest = sha256Digest.digest(data)
      val base64 = Base64.getUrlEncoder.withoutPadding().encodeToString(digest)

      base64
    }

    def readRandomBytes(nBytes: Int): Array[Byte] = {

      val size = file.size
      val bytes = new Array[Byte](nBytes)
      val random = new Random(size)

      file.randomAccess().foreach { rndAccess =>
        (0 until nBytes).map { i =>
          val pos = (random.nextDouble() * (size -1)).toLong
          try {
            rndAccess.seek(pos)
            bytes(i) = rndAccess.readByte()
          } catch {
            case _: Exception =>
              logger.warn(s"Failed reading byte at: ${file.name} ($size): $i, $pos")
          }
        }
      }

      bytes
    }

    val bytes = readRandomBytes(1024)

    md5hash(bytes)
  }

  def ffprobe(file: File): Probe = {

    val fileName = file.path.toAbsolutePath.toString
    val output = run("ffprobe", fileName)

    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
    val res = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

    val (w, h) = output match {
      case res(w, h) =>
        (w.toInt, h.toInt)
      case _         =>
        logger.warn("Could not extract resolution")
        (0, 0)
    }

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt *  60 * 60 * 1000 +
        minutes.toInt * 60 * 1000 +
        seconds.toInt * 1000
    }

    val hash = fakeHash(file)

    Probe(hash, fileName, duration, (w, h))
  }

  def writeThumbnail(inputFile: String, time: Long, outputFile: Option[String]): Unit = {

    val baseFilename = inputFile.substring(0, inputFile.lastIndexOf('.'))
    val thumbnailFile = File(outputFile.getOrElse(s"$baseFilename.jpeg"))

    if (!thumbnailFile.exists) {

      logger.debug(s"Creating thumbnail for $inputFile")

      val timestamp = Duration.ofMillis(time)
      val ss = s"${timestamp.toHoursPart}:${timestamp.toMinutesPart}:${timestamp.toSecondsPart}"

      run(s"ffmpeg", "-ss", ss, "-i", inputFile, "-vframes", "1", thumbnailFile.path.toAbsolutePath.toString)
    }
  }

  def run(cmds: String*): String = {

    val r = Runtime.getRuntime
    val p = r.exec(cmds.toArray)
    val is = p.getErrorStream
    val output = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }
}
