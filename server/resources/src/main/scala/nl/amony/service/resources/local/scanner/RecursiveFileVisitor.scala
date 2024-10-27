package nl.amony.service.resources.local.scanner

import scribe.Logging

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

class RecursiveFileVisitor(fileNameFilter: Path => Boolean) extends SimpleFileVisitor[Path] with Logging:

//  val files = mutable.ListBuffer.empty[Path]
  var files = Seq.empty[(Path, BasicFileAttributes)]

  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (dir.getFileName.toString.startsWith("."))
      FileVisitResult.SKIP_SUBTREE
    else
      FileVisitResult.CONTINUE

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (fileNameFilter(file))
      files = (file, attrs) +: files

    FileVisitResult.CONTINUE

  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = 
    logger.warn(s"Failed to visit path: $file")
    FileVisitResult.CONTINUE

object RecursiveFileVisitor:
  def listFilesInDirectoryRecursive(dir: Path, filter: Path => Boolean): Seq[(Path, BasicFileAttributes)] =
    
    val visitor = new RecursiveFileVisitor(filter)
    Files.walkFileTree(dir, visitor)
    visitor.files

