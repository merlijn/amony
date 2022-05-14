package nl.amony.lib

import better.files.File
import nl.amony.Main.logger
import nl.amony.lib.hash.Base32
import scribe.Logging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
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

    def absoluteFileName(): String = path.toAbsolutePath.normalize().toString

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

  def watchPath(path: Path) = {

    import io.methvin.watcher.DirectoryChangeEvent.EventType._
    import io.methvin.watcher._

    val watcher = DirectoryWatcher.builder.path(path).listener {
      (event: DirectoryChangeEvent) => {
        event.eventType match {
          case CREATE => logger.info(s"File created : ${event.path}")
          case MODIFY => logger.info(s"File modified: ${event.path}")
          case DELETE => logger.info(s"File deleted : ${event.path}")
        }
      }
    }.fileHashing(false).build

    watcher.watchAsync()
  }
}
