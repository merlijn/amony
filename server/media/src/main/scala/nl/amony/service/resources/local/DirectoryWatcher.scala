package nl.amony.service.resources.local

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.methvin.watcher.DirectoryChangeEvent.EventType._
import io.methvin.watcher._
import nl.amony.lib.akka.AtLeastOnceProcessor
import nl.amony.lib.files.{FileUtil, PathOps}
import nl.amony.service.media.MediaConfig.{HashingAlgorithm, LocalResourcesConfig}
import nl.amony.service.resources.local.DirectoryEvents.{DirectoryEvent, FileAdded, FileDeleted, FileMoved}
import nl.amony.service.resources.local.DirectoryWatcher.{DirectoryState, FileInfo}
import scribe.Logging

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

object DirectoryEvents extends Logging {

  sealed trait DirectoryEvent

  case class FileAdded(file: FileInfo) extends DirectoryEvent
  case class FileMoved(oldPath: String, newPath: String) extends DirectoryEvent
  case class FileDeleted(path: String) extends DirectoryEvent

  def apply(state: DirectoryState, event: DirectoryEvent): DirectoryState = event match {

    case FileAdded(info) =>
      state.copy(files = state.files + info)

    case FileMoved(oldPath, newPath) =>
      val oldFile = state.getByPath(oldPath).get
      val newFile = oldFile.copy(relativePath = newPath)
      state.copy(files = state.files - oldFile + newFile)

    case FileDeleted(path) =>
      val prev = state.files.find(_.relativePath == path)
      state.copy(files = state.files -- prev)
  }
}

object DirectoryWatcher extends Logging {

  def watchAsync(path: Path, actorRef: ActorRef[DirectoryCommand]) = {

    val watcher =  io.methvin.watcher.DirectoryWatcher.builder.path(path).listener {
      (event: DirectoryChangeEvent) => {
          logger.debug(s"Received directory event ${event.eventType()} for ${event.path()}")
          event.eventType match {
            case CREATE => actorRef.tell(InternalDirectoryEvent(Added, event.path()))
            case MODIFY => actorRef.tell(InternalDirectoryEvent(Modified, event.path()))
            case DELETE => actorRef.tell(InternalDirectoryEvent(Deleted, event.path()))
            case OVERFLOW =>
          }
      }
    }.fileHashing(false).build

    watcher.watchAsync()
  }

  object DirectoryState {
    val empty = DirectoryState(Set.empty)
  }
  case class DirectoryState(files: Set[FileInfo]) {

    def getByHash(hash: String): Option[FileInfo] = files.find(_.hash == hash)
    def getByPath(path: String): Option[FileInfo] = files.find(_.relativePath == path)
  }

  case class GetIndex(sender: ActorRef[Set[FileInfo]]) extends DirectoryCommand

  case class FileInfo(hash: String,
                      relativePath: String,
                      size: Long,
                      createdTimestamp: Long,
                      changedTimestamp: Long)

  sealed trait DirectoryCommand

  sealed trait EventType
  case object Added extends EventType
  case object Modified extends EventType
  case object Deleted extends EventType

  private[resources] case class DeleteFile(info: FileInfo) extends DirectoryCommand
  private[resources] case class InternalDirectoryEvent(eventType: EventType, path: Path) extends DirectoryCommand

  def persistenceId(id: String) = s"directory-$id"

  def behavior(config: LocalResourcesConfig): Behavior[DirectoryCommand] = {

    Behaviors.setup[DirectoryCommand] { context =>

      val filter: Path => Boolean = path => {
        !path.startsWith(config.resourcePath) &&
        !path.startsWith(config.getIndexPath()) &&
        config.filterFileName(path.toString)
      }

//      val processor =
//        AtLeastOnceProcessor.process[DirectoryEvent](DirectoryWatcher.persistenceId(config.id), "test",
//          (e: DirectoryEvent) => println(s"Processed $e"))
//
//      context.spawn(processor, "directory-processor")

      behavior(config.id, config.hashingAlgorithm, config.mediaPath, filter)
    }
  }

  def behavior(id: String, hashingAlgorithm: HashingAlgorithm, directoryPath: Path, pathFilter: Path => Boolean): Behavior[DirectoryCommand] =
    Behaviors.setup { context =>

      watchAsync(directoryPath, context.self)

      EventSourcedBehavior.apply[DirectoryCommand, DirectoryEvent, DirectoryState](
        persistenceId  = PersistenceId.ofUniqueId(persistenceId(id)),
        emptyState     = DirectoryState.empty,
        commandHandler = commandHandler(context, directoryPath, pathFilter, hashingAlgorithm),
        eventHandler   = DirectoryEvents.apply
      ).receiveSignal {
        case (state, RecoveryCompleted) =>
          logger.info(s"Recovered ${state.files.size} files, scanning: $directoryPath")
          FileUtil
            .listFilesInDirectoryRecursive(directoryPath).toVector
            .foreach { path => context.self ! InternalDirectoryEvent(Added, path) }
      }
    }

  def commandHandler(context: ActorContext[DirectoryCommand],
                     directoryPath: Path,
                     pathFilter: Path => Boolean,
                     hashingAlgorithm: HashingAlgorithm): (DirectoryState, DirectoryCommand) => Effect[DirectoryEvent, DirectoryState] = {

    val deleted = scala.collection.mutable.Set.empty[FileInfo]

    (state, msg) => {

      msg match {

        case InternalDirectoryEvent(e, path) if !pathFilter(path) =>

          logger.debug(s"Ignored $e event for $path")
          Effect.none

        case InternalDirectoryEvent(Added, path) if Files.isRegularFile(path) =>

          val relativePath = directoryPath.relativize(path).toString

          def hasEqualMetaData(info: FileInfo) =
            info.size == path.size() &&
              info.changedTimestamp == path.lastModifiedMillis() &&
              info.createdTimestamp == path.creationTimeMillis()

          def newFile(): FileInfo = FileInfo(
            hash = hashingAlgorithm.createHash(path),
            relativePath = directoryPath.relativize(path).toString,
            size = path.size(),
            createdTimestamp = path.creationTimeMillis(),
            changedTimestamp = path.lastModifiedMillis(),
          )

          /**
           * This attempts to detect file renames/moves without recomputing the hash
           * The idea is:
           * If a file with equal meta data (size, created & last modified timestamp) was recently deleted
           * then likely this is the same file and we can re-use the hash of it.
           */
          state.files.find(hasEqualMetaData) match {
            case None =>
              logger.debug(s"File added: $relativePath")
              Effect.persist(FileAdded(newFile))
            case Some(file) if file.relativePath == relativePath =>
              logger.debug(s"File already added: $relativePath")
              Effect.none
            case Some(file) if !Files.exists(directoryPath.resolve(file.relativePath)) =>
              logger.debug(s"File moved: $relativePath")
              Effect
                .persist(FileMoved(file.relativePath, relativePath))
                .thenRun { s: DirectoryState => deleted -= file }
            case other =>
              logger.warn(s"Unexpected situation for $relativePath")
              Effect.none
          }

        case InternalDirectoryEvent(Added, path) =>
          logger.info(s"Directory added: $path")
          Effect.none

        case InternalDirectoryEvent(Modified, path) =>
          logger.info(s"Modified: $path")
          // should be recently modified
          if (System.currentTimeMillis() - path.lastModifiedMillis < 1000)
            println(s"Modified: $path")

          Effect.none

        case InternalDirectoryEvent(Deleted, path) =>

          val relativePath = directoryPath.relativize(path).toString

          state.files.find(_.relativePath == relativePath) foreach {
            info =>
              context.scheduleOnce(1.seconds, context.self, DeleteFile(info))
              deleted += info
          }

          Effect.none

        case DeleteFile(info) =>

          if (deleted.contains(info))
            Effect.persist(FileDeleted(info.relativePath)).thenRun { _ => deleted -= info }
          else
            Effect.none

        case GetIndex(sender) =>
          Effect.reply(sender)(state.files)

      }
    }
  }
}
