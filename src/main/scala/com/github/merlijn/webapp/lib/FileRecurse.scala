package com.github.merlijn.webapp.lib

import com.github.merlijn.webapp.Logging

import java.io.IOException
import java.nio.file.{FileVisitResult, FileVisitor, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable

class FileRecurse() extends FileVisitor[Path] with Logging {

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
  override def postVisitDirectory(dir: Path, exc: IOException) = FileVisitResult.CONTINUE

  @throws[IOException]
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    files.addOne(file)
    FileVisitResult.CONTINUE
  }

  @throws[IOException]
  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = { // This is important to note. Test this behaviour

    logger.warn(s"Failed to visit file: $file")
    FileVisitResult.CONTINUE
  }
}