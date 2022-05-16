package nl.amony.lib

import scribe.Logging

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable

class RecursiveFileVisitor extends SimpleFileVisitor[Path] with Logging {

  private val files = mutable.ListBuffer.empty[Path]

  def getFiles(): Iterable[Path] = files

  @throws[IOException]
  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {

    if (dir.getFileName.toString.startsWith("."))
      FileVisitResult.SKIP_SUBTREE
    else
      FileVisitResult.CONTINUE
  }

  @throws[IOException]
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    files.addOne(file)
    FileVisitResult.CONTINUE
  }

  @throws[IOException]
  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
    logger.warn(s"Failed to visit path: $file")
    FileVisitResult.CONTINUE
  }
}
