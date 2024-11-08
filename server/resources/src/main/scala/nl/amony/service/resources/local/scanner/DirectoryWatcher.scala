package nl.amony.service.resources.local.scanner

import cats.effect.IO

import java.nio.file.*
import java.util.concurrent.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait FileStore {
  def getByPath(path: Path): Option[FileInfo]
  def deletePath(path: Path): Unit
  def getAll(): Seq[FileInfo]
}

object DirectoryWatcher {

  val logger = scribe.Logger("DirectoryWatcher")

  sealed trait WatchEvent

  case class FileCreated(path: Path) extends WatchEvent
  case class FileModified(path: Path) extends WatchEvent
  case class FileDeleted(path: Path) extends WatchEvent
  case class FileMoved(oldPath: Path, newPath: Path) extends WatchEvent

  def watchDirectory(directoryPath: Path, getByPath: Path => Option[FileInfo], hashFn: Path => String): Flow.Publisher[WatchEvent] = {
    val publisher = new SubmissionPublisher[WatchEvent]()
    val watchService: WatchService = FileSystems.getDefault.newWatchService()

    val deletedFilesBuffer = scala.collection.mutable.Map[Path, FileInfo]()
    val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    def registerDirectory(dir: Path): Unit = {
      dir.register(
        watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE
      )
      Files.list(dir).iterator().asScala.foreach { path =>
        if (Files.isDirectory(path)) registerDirectory(path)
      }
    }

    registerDirectory(directoryPath)

    def publishDeletedEvent(path: Path): Runnable = () => {
      if (deletedFilesBuffer.contains(path)) {
        logger.debug(s"File deleted: $path")
        publisher.submit(FileDeleted(path))
        deletedFilesBuffer.remove(path)
      }
    }

    new Thread(() => {
      try {
        while (true) {
          val key = watchService.take()
          key.pollEvents().forEach { event =>
            val kind = event.kind()
            lazy val path = directoryPath.resolve(event.context().asInstanceOf[Path])
            val fileInfo  = getByPath(path)

            kind match
              case StandardWatchEventKinds.ENTRY_DELETE =>
                logger.debug(s"File deleted: $path")
                scheduledExecutor.schedule(publishDeletedEvent(path), 100, TimeUnit.MILLISECONDS)
              case StandardWatchEventKinds.ENTRY_CREATE =>
                if (Files.isDirectory(path)) registerDirectory(path)

                val hash = hashFn(path)
                logger.debug(s"File created: $path")
                publisher.submit(FileCreated(path))
              case StandardWatchEventKinds.ENTRY_MODIFY =>
                logger.debug(s"File modified: $path")
                publisher.submit(FileModified(path))
          }

          key.reset()
        }
      } catch {
        case _: InterruptedException => // Thread interrupted, exit loop
      } finally {
        Using.resource(watchService)(_.close())
        publisher.close()
      }
    }).start()

    publisher
  }

  def watch(rootPath: Path, getByPath: Path => Option[FileInfo]): fs2.Stream[IO, WatchEvent] = {
    val watchDirectoryPublisher = watchDirectory(rootPath, getByPath, path => path.toString)
    fs2.Stream.fromPublisher[IO](watchDirectoryPublisher, 1)
  }
}