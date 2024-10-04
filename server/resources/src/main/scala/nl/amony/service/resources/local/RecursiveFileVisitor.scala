package nl.amony.service.resources.local

import scribe.Logging

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import scala.collection.mutable

class RecursiveFileVisitor(fileNameFilter: Path => Boolean) extends SimpleFileVisitor[Path] with Logging:

  val files = mutable.ListBuffer.empty[Path]

  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (dir.startsWith("."))
      FileVisitResult.SKIP_SUBTREE
    else
      FileVisitResult.CONTINUE

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (fileNameFilter(file))
      files.addOne(file)

    FileVisitResult.CONTINUE

  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = 
    logger.warn(s"Failed to visit path: $file")
    FileVisitResult.CONTINUE

object RecursiveFileVisitor:
  def listFilesInDirectoryRecursive(dir: Path, fileNameFilter: Path => Boolean): Iterable[Path] =
    
    val visitor = new RecursiveFileVisitor(fileNameFilter)
    Files.walkFileTree(dir, visitor)
    visitor.files

