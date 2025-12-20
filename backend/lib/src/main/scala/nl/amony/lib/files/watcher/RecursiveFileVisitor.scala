package nl.amony.lib.files.watcher

import cats.effect.IO
import fs2.Stream
import scribe.Logging

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

class RecursiveFileVisitor(directoryFilter: Path => Boolean, fileNameFilter: Path => Boolean) extends SimpleFileVisitor[Path] with Logging:

//  val files = mutable.ListBuffer.empty[Path]
  var files = Seq.empty[(Path, BasicFileAttributes)]

  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (directoryFilter(dir))
      FileVisitResult.CONTINUE
    else
      FileVisitResult.SKIP_SUBTREE

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
    if (fileNameFilter(file))
      files = (file, attrs) +: files

    FileVisitResult.CONTINUE

  override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = 
    logger.warn(s"Failed to visit path: $file")
    FileVisitResult.CONTINUE

object RecursiveFileVisitor extends Logging:
  def listFilesInDirectoryRecursive(dir: Path, directoryFilter: Path => Boolean, fileFilter: Path => Boolean): Seq[(Path, BasicFileAttributes)] =
    
    val visitor = new RecursiveFileVisitor(directoryFilter, fileFilter)
    Files.walkFileTree(dir, visitor)
    logger.info("Scanned directory: " + dir.toString + ", found files: " + visitor.files.size)
    visitor.files
    
  def streamFilesInDirectoryRecursive(dir: Path, directoryFilter: Path => Boolean, fileFilter: Path => Boolean): Stream[IO, (Path, BasicFileAttributes)] =
    fs2.Stream.emits(listFilesInDirectoryRecursive(dir, directoryFilter, fileFilter))

