package nl.amony.lib

import scribe.Logging

import java.nio.file.Path

object FileUtil extends Logging {

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

//  def watchPath(path: Path) = {
//
//    val watcher = DirectoryWatcher.builder.path(path).listener {
//      (event: DirectoryChangeEvent) => {
//        event.eventType match {
//          case CREATE => logger.info(s"File created : ${event.path}")
//          case MODIFY => logger.info(s"File modified: ${event.path}")
//          case DELETE => logger.info(s"File deleted : ${event.path}")
//        }
//      }
//    }.fileHashing(false).build
//
//    watcher.watchAsync()
//  }
}
