package nl.amony.lib

import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.io.Codec

package object files {

  extension(path: Path) {

    // strip extension
    def stripExtension(): Path = {
      val fileName = path.getFileName.toString
      val dotIdx = fileName.lastIndexOf('.')
      val last   = if (dotIdx >= 0) dotIdx else fileName.length
      path / fileName.substring(0, last)
    }

    def fileExtension(): Option[String] = {
      val dotIdx = path.toString.lastIndexOf('.')
      if (dotIdx >= 0)
        Some(path.toString.substring(dotIdx + 1))
      else
        None
    }

    def basicFileAttributes(): BasicFileAttributes =
      Files.readAttributes(path, classOf[BasicFileAttributes])

    def creationTimeMillis(): Long =
      basicFileAttributes().creationTime().toMillis

    def lastModifiedMillis(): Long =
      basicFileAttributes().lastModifiedTime().toMillis

    def fileNameWithoutExtension(): String =
      FileUtil.stripExtension(path.getFileName.toString)

    def absoluteFileName(): String =
      path.toAbsolutePath.normalize().toString

    def size(): Long = Files.size(path)

    def contentAsString(charset: Charset) =
      scala.io.Source.fromFile(path.toFile)(new Codec(charset)).mkString

    def exists(): Boolean =
      Files.exists(path)

    def deleteIfExists(): Unit =
      if (Files.exists(path))
        Files.delete(path)

    def /(child: String): Path = path.resolve(child)
    def /(child: Path): Path = path.resolve(child)
  }
}

