package nl.amony.service.resources.local

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import io.methvin.watcher.DirectoryChangeEvent.EventType._
import io.methvin.watcher._
import nl.amony.lib.files.PathOps
import nl.amony.service.media.MediaConfig.HashingAlgorithm
import scribe.Logging

import java.nio.file.{Files, Path}

object DirectoryWatcher extends Logging {

  def watchPath(path: Path, actorRef: ActorRef[LocalDirectoryCommand]) = {

    val watcher =  io.methvin.watcher.DirectoryWatcher.builder.path(path).listener {
      (event: DirectoryChangeEvent) => {
        event.eventType match {
          case CREATE => actorRef.tell(DirectoryEvent(Added, event.path()))
          case MODIFY => actorRef.tell(DirectoryEvent(Modified, event.path()))
          case DELETE => actorRef.tell(DirectoryEvent(Deleted, event.path()))
        }
      }
    }.fileHashing(false).build

    watcher.watchAsync()
  }

  object DirectoryState {
    val empty = DirectoryState(Set.empty, Set.empty)
  }
  case class DirectoryState(files: Set[LocalFileInfo], deleted: Set[LocalFileInfo])

  case class GetIndex(sender: ActorRef[Set[LocalFileInfo]]) extends LocalDirectoryCommand

  case class LocalFileInfo(relativePath: String,
                           size: Long,
                           createdTimestamp: Long,
                           changedTimestamp: Long,
                           hashes: List[String])

  sealed trait LocalDirectoryCommand

  sealed trait EventType
  case object Added extends EventType
  case object Modified extends EventType
  case object Deleted extends EventType

  private[resources] case class DirectoryEvent(eventType: EventType, path: Path) extends LocalDirectoryCommand
  def ofInterest(path: Path): Boolean = Files.isRegularFile(path) //&& path.fileExtension == Some(".mp4")

  def apply(hashingAlgorithm: HashingAlgorithm, dir: Path): Behavior[LocalDirectoryCommand] =
    Behaviors.setup { context =>
      watchPath(dir, context.self)
      watch(hashingAlgorithm, dir)
    }

  def watch(hashingAlgorithm: HashingAlgorithm, dir: Path, state: DirectoryState = DirectoryState.empty): Behavior[LocalDirectoryCommand] =

    Behaviors.receiveMessagePartial {

      case DirectoryEvent(Added, path) if ofInterest(path) =>

        val hash = state.deleted.find { info =>
          info.size > 512 &&
          info.size == path.size() &&
          info.changedTimestamp == path.lastModifiedMillis() &&
          info.createdTimestamp == path.creationTimeMillis()
        } match {
          case Some(info) =>
            info.hashes.head
          case None =>
            hashingAlgorithm.generateHash(path)
        }

        val localFileInfo = LocalFileInfo(
          relativePath = dir.relativize(path).toString,
          size = path.size(),
          createdTimestamp = path.creationTimeMillis(),
          changedTimestamp = path.lastModifiedMillis(),
          hashes = List(hash)
        )

        val newState = state.copy(files = state.files + localFileInfo)

        watch(hashingAlgorithm, dir, newState)

      case DirectoryEvent(Modified, path) if ofInterest(path) =>

        // should be recently modified
        if (System.currentTimeMillis() - path.lastModifiedMillis < 1000)
          println(s"Modified: $path")

        Behaviors.same

      case DirectoryEvent(Deleted, path) =>

        val relativePath = dir.relativize(path).toString
        state.files.find(_.relativePath == relativePath) match {
          case Some(info) =>
            println(s"Deleted known file: $path")
            val newState = state.copy(deleted = state.deleted + info)
            watch(hashingAlgorithm, dir, newState)
          case None =>
            Behaviors.same
        }

      case GetIndex(sender) =>
        sender.tell(state.files)
        Behaviors.same
    }

}
