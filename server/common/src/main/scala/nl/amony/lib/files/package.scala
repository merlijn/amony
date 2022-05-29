package nl.amony.lib

import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.io.Codec

package object files {

  implicit class PathOps(path: Path) {

    // strip extension
    def stripExtension(): String = {
      val dotIdx = path.toString.lastIndexOf('.')
      val last   = if (dotIdx >= 0) dotIdx else path.toString.length
      path.toString.substring(0, last)
    }

    def withExtension(ext: String) = {

    }

    def fileExtension(): Option[String] = {
      val dotIdx = path.toString.lastIndexOf('.')
      if (dotIdx >= 0)
        Some(path.toString.substring(dotIdx + 1))
      else
        None
    }

    def basicFileAttributes(): BasicFileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

    def creationTimeMillis(): Long = basicFileAttributes().creationTime().toMillis

    def lastModifiedMillis(): Long = basicFileAttributes().lastModifiedTime().toMillis

    def absoluteFileName(): String = path.toAbsolutePath.normalize().toString

    def size(): Long = Files.size(path)

    def contentAsString(charset: Charset) =
      scala.io.Source.fromFile(path.toFile)(new Codec(charset)).mkString

    def deleteIfExists(): Unit = {
      if (Files.exists(path))
        Files.delete(path)
    }

    def /(child: String): Path = path.resolve(child)
  }
}
