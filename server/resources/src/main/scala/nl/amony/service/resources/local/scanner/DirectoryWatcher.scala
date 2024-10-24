package nl.amony.service.resources.local.scanner

import cats.effect.IO

import java.nio.file.*
import java.util.concurrent.Flow
import java.util.concurrent.SubmissionPublisher
import scala.jdk.CollectionConverters.*
import scala.util.Using

object DirectoryWatcher {

  val logger = scribe.Logger("DirectoryWatcher")

  sealed trait WatchEvent

  case class FileCreated(path: Path) extends WatchEvent
  case class FileModified(path: Path) extends WatchEvent
  case class FileDeleted(path: Path) extends WatchEvent

  def watchDirectory(rootPath: Path): Flow.Publisher[WatchEvent] = {
    val publisher = new SubmissionPublisher[WatchEvent]()

    val watchService: WatchService = FileSystems.getDefault.newWatchService()

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

    registerDirectory(rootPath)

    new Thread(() => {
      try {
        while (true) {
          val key = watchService.take()
          key.pollEvents().forEach { event =>

            val kind = event.kind()
            lazy val path = rootPath.resolve(event.context().asInstanceOf[Path])

            val watchEvent = PartialFunction.condOpt(kind):
              case StandardWatchEventKinds.ENTRY_DELETE =>
                logger.debug(s"File deleted: $path")
                FileDeleted(path)
              case StandardWatchEventKinds.ENTRY_CREATE =>
                if (Files.isDirectory(path)) registerDirectory(path)
                logger.debug(s"File created: $path")
                FileCreated(path)
              case StandardWatchEventKinds.ENTRY_MODIFY =>
                logger.debug(s"File modified: $path")
                FileModified(path)

            watchEvent.foreach(publisher.submit)
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

  def foo(rootPath: Path) = {
    val watchDirectoryPublisher = watchDirectory(rootPath)
    fs2.Stream.fromPublisher[IO](watchDirectoryPublisher, 1)
  }
}