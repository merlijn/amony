package nl.amony.lib

import better.files.File
import scribe.Logging

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import scala.util.Random

object FileUtil extends Logging {

  implicit class PathOps(path: Path) {

    // strip extension
    def stripExtension(): String = {
      val dotIdx = path.toString.lastIndexOf('.')
      val last   = if (dotIdx >= 0) dotIdx else path.toString.length
      path.toString.substring(0, last)
    }

    def creationTimeMillis(): Long = {
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      attributes.creationTime().toMillis
    }

    def absoluteFileName(): String = path.toAbsolutePath.toString

    def deleteIfExists(): Unit = {
      val f = File(path)
      if (f.exists)
        f.delete()
    }

    def /(child: String): Path = path.resolve(child)
  }

  // strip extension
  def stripExtension(fileName: String): String = {
    val dotIdx = fileName.lastIndexOf('.')
    val last   = if (dotIdx >= 0) dotIdx else fileName.length
    fileName.substring(0, last)
  }

  def extension(fileName: String): String = {
    val dotIdx = fileName.lastIndexOf('.')
    val maxIdx = fileName.length - 1
    val first  = if (dotIdx >= 0) Math.min(dotIdx, maxIdx) else maxIdx
    fileName.substring(first, fileName.length)
  }

  def walkDir(dir: Path): Iterable[Path] = {
    import java.nio.file.Files

    val r = new RecursiveFileVisitor
    Files.walkFileTree(dir, r)
    r.getFiles()
  }

  // samples a file randomly and creates a hash from that
  def partialMD5Hash(file: File, nBytes: Int = 512): String = {

    def md5hashInBase64(data: Array[Byte]): String = {
      import java.security.MessageDigest

      val md5Digest: MessageDigest = MessageDigest.getInstance("MD5")
      val digest                   = md5Digest.digest(data)
      val base64                   = Base64.getUrlEncoder.withoutPadding().encodeToString(digest)

      base64
    }

    def readRandomBytes(): Array[Byte] = {

      val size   = file.size
      val bytes  = new Array[Byte](nBytes)
      val random = new Random(size)

      file.randomAccess().foreach { rndAccess =>
        (0 until nBytes).map { i =>
          val pos = (random.nextDouble() * (size - 1)).toLong
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

    val bytes = readRandomBytes()

    md5hashInBase64(bytes).substring(0, 8)
  }
}
