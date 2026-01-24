package nl.amony.lib.files.watcher

import java.nio.file.*
import java.util.concurrent.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO

/**
 * OpenJDK does not support efficient directory watching on MacOS
 * https://github.com/openjdk/jdk/pull/10140
 * The JetBrains runtime does apparently
 * https://github.com/JetBrains/JetBrainsRuntime
 */
object DirectoryWatcher {

  val logger = scribe.Logger("DirectoryWatcher")

  def watchDirectory(directoryPath: Path, getByPath: Path => Option[FileInfo], hashFn: Path => String): Flow.Publisher[FileEvent] = {
    val publisher                  = new SubmissionPublisher[FileEvent]()
    val watchService: WatchService = FileSystems.getDefault.newWatchService()

    val deletedFilesBuffer                          = scala.collection.mutable.Map[Path, FileInfo]()
    val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    def registerDirectory(dir: Path): Unit = {
      dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
      Files.list(dir).iterator().asScala.foreach(path => if Files.isDirectory(path) then registerDirectory(path))
    }

    registerDirectory(directoryPath)

    def publishDeletedEvent(path: Path): Runnable = () => {
      deletedFilesBuffer.get(path).foreach {
        fileInfo =>
          logger.debug(s"File deleted: $path")
          publisher.submit(FileDeleted(fileInfo))
      }
    }

    new Thread(() => {
      try {
        while true do {
          val key = watchService.take()
          key.pollEvents().forEach {
            event =>
              val kind      = event.kind()
              lazy val path = directoryPath.resolve(event.context().asInstanceOf[Path])
              val fileInfo  = getByPath(path)

              kind match
                case StandardWatchEventKinds.ENTRY_DELETE => scheduledExecutor.schedule(publishDeletedEvent(path), 100, TimeUnit.MILLISECONDS)
                case StandardWatchEventKinds.ENTRY_CREATE =>
                  if Files.isDirectory(path) then registerDirectory(path)

                  val hash = hashFn(path)
                  logger.debug(s"File created: $path")
                  publisher.submit(FileAdded(FileInfo(path, hash)))
                case StandardWatchEventKinds.ENTRY_MODIFY => logger.debug(s"File modified: $path")
//                publisher.submit(FileDeleted(path))
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

  def watch(rootPath: Path, getByPath: Path => Option[FileInfo]): fs2.Stream[IO, FileEvent] =
    val watchDirectoryPublisher = watchDirectory(rootPath, getByPath, path => path.toString)
    fs2.Stream.fromPublisher[IO](watchDirectoryPublisher, 1)
}
