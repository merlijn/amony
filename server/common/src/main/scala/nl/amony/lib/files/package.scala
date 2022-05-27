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

    def fileExtension(): Option[String] = {
      val dotIdx = path.toString.lastIndexOf('.')
      if (dotIdx >= 0)
        Some(path.toString.substring(dotIdx + 1))
      else
        None
    }

    def creationTimeMillis(): Long = {
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      attributes.creationTime().toMillis
    }

    def lastModifiedMillis(): Long = {
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      attributes.lastModifiedTime().toMillis
    }

    def absoluteFileName(): String = path.toAbsolutePath.normalize().toString

    def size(): Long = Files.size(path)

    def contentAsString(charset: Charset) =
      scala.io.Source.fromFile(path.toFile)(new Codec(charset)).mkString

    def deleteIfExists(): Unit = {
      if (Files.exists(path))
        Files.delete(path)
    }

//    def writeContent(content: String) =
//      Files.write(path, )

    def /(child: String): Path = path.resolve(child)
  }
}
