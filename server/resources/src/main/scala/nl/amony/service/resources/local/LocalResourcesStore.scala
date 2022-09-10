package nl.amony.service.resources.local

import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.util.FastFuture
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.scaladsl.{FileIO, Sink}
import akka.stream.{SourceRef, SystemMaterializer}
import akka.util.ByteString
import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import nl.amony.lib.akka.EventProcessing.ProcessedState
import nl.amony.lib.akka.{GraphShapes, ServiceBehaviors}
import nl.amony.lib.files.{FileUtil, PathOps}
import nl.amony.service.resources.ResourceConfig.{DeleteFile, LocalResourcesConfig, MoveToTrash}
import scribe.Logging

import java.awt.Desktop
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.Future
import scala.util.{Failure, Success}

object LocalResourcesStore extends Logging {

  def persistenceId(directoryId: String) = s"local-resources-$directoryId"

  sealed trait LocalResourceEvent

  case class FileAdded(file: LocalFile) extends LocalResourceEvent
  case class FileDeleted(hash: String, relativePath: String) extends LocalResourceEvent
  case class FileMoved(hash: String, oldPath: String, newPath: String) extends LocalResourceEvent

  case class LocalFile(
      relativePath: String,
      hash: String,
      size: Long,
      creationTime: Long,
      lastModifiedTime: Long) {
    def extension: String = relativePath.split('.').last
    def hasEqualMeta(other: LocalFile) = {
      // this depends on file system meta data and the fact that a file move does not update these attributes
      hash == other.hash && creationTime == other.creationTime && lastModifiedTime == other.lastModifiedTime
    }
  }

  sealed trait LocalResourceCommand

  object LocalResourceCommand {
    implicit val serviceKey = ServiceKey[LocalResourceCommand]("local-resources-store")
  }

  case class GetByHash(hash: String, sender: ActorRef[Option[LocalFile]]) extends LocalResourceCommand
  case class GetAll(sender: ActorRef[Set[LocalFile]]) extends LocalResourceCommand
  case class FullScan(sender: ActorRef[Set[LocalFile]]) extends LocalResourceCommand
  case class Upload(fileName: String, source: SourceRef[ByteString], sender: ActorRef[Boolean]) extends LocalResourceCommand
  case class DeleteFileByHash(hash: String, sender: ActorRef[Boolean]) extends LocalResourceCommand

  private case class UploadCompleted(relativePath: String, hash: String, originalSender: ActorRef[Boolean]) extends LocalResourceCommand

  def scanDirectory(config: LocalResourcesConfig, snapshot: Set[LocalFile]): Observable[LocalFile] = {
    Observable
      .from(FileUtil.listFilesInDirectoryRecursive(config.mediaPath))
      .filter { file => config.filterFileName(file.getFileName.toString) }
      .filterNot { file =>
        val isEmpty = Files.size(file) == 0
        if (isEmpty)
          logger.warn(s"Encountered empty file: ${file.getFileName.toString}")
        isEmpty
      }
      .mapParallelUnordered(config.scanParallelFactor) { path =>
        Task {

          val relativePath = config.mediaPath.relativize(path).toString
          val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

          val hash = if (config.verifyExistingHashes) {
            config.hashingAlgorithm.createHash(path)
          } else {
            snapshot.find(_.relativePath == relativePath) match {
              case None => config.hashingAlgorithm.createHash(path)
              case Some(m) =>
                if (m.lastModifiedTime != fileAttributes.lastModifiedTime().toMillis) {
                  logger.warn(s"$path last modified time is different from what last seen, recomputing hash")
                  config.hashingAlgorithm.createHash(path)
                } else {
                  m.hash
                }
            }
          }

          LocalFile(relativePath, hash, fileAttributes.size(), fileAttributes.creationTime().toMillis, fileAttributes.lastModifiedTime().toMillis)
        }
      }
  }

  def behavior(config: LocalResourcesConfig): Behavior[LocalResourceCommand] =
    ServiceBehaviors.setupAndRegister[LocalResourceCommand] { context =>

      implicit val ec = context.executionContext
      implicit val sc = context.system.scheduler

      EventSourcedBehavior[LocalResourceCommand, LocalResourceEvent, Set[LocalFile]](
        persistenceId  = PersistenceId.ofUniqueId(persistenceId(config.id)),
        emptyState     = Set.empty[LocalFile],
        commandHandler = commandHandler(config, context),
        eventHandler   = eventSource
      ).receiveSignal {
        case (_, RecoveryCompleted) =>
          // this forces a full directory scan on start up
          context.self ! FullScan(context.system.ignoreRef)
      }
    }


  def eventSource(state: Set[LocalFile], e: LocalResourceEvent): Set[LocalFile] = e match {
    case FileAdded(resource) =>
      state + resource
    case FileDeleted(hash, relativePath) =>
      state -- state.find(r => r.relativePath == relativePath && r.hash == hash)
    case FileMoved(hash, oldPath, newPath) =>
      val old = state.find(r => r.relativePath == oldPath && r.hash == hash)
      state -- old ++ old.map(_.copy(relativePath = newPath))
  }

  def commandHandler(config: LocalResourcesConfig, context: ActorContext[LocalResourceCommand])(state: Set[LocalFile], cmd: LocalResourceCommand): Effect[LocalResourceEvent, Set[LocalFile]] = {

    implicit val monixScheduler = monix.execution.Scheduler.Implicits.global
    implicit val mat = SystemMaterializer.get(context.system).materializer

    cmd match {

      case GetAll(sender) =>
        Effect.reply(sender)(state)

      case GetByHash(hash, sender) =>
        Effect.reply(sender)(state.find(_.hash == hash))

      case FullScan(sender) =>
        val scannedResources =
          scanDirectory(config, state)
            .consumeWith(Consumer.toList)
            .runSyncUnsafe().toSet

        val (colliding, nonColliding) = scannedResources
          .groupBy { _.hash }
          .partition { case (_, files) => files.size > 1 }

        colliding.foreach { case (hash, files) =>
          val collidingFiles = files.map(_.relativePath).mkString("\n")
          logger.warn(s"The following files share the same hash and will be ignored ($hash):\n$collidingFiles")
        }

        val nonCollidingResources = nonColliding.map(_._2).flatten

        val newResources: List[FileAdded] =
          nonCollidingResources
            .filterNot(r => state.exists(_.hash == r.hash))
            .map(r => FileAdded(r))
            .toList

        val deletedResources: List[FileDeleted] =
          state
            .filterNot(r => scannedResources.exists(_.hash == r.hash))
            .map(r => FileDeleted(r.hash, r.relativePath))
            .toList

        val movedResources: List[FileMoved] =
          state.flatMap { old =>

            def equalMeta() = scannedResources.find { n => old.relativePath != n.relativePath && old.hasEqualMeta(n) }
            def equalHash() = scannedResources.find { n => old.relativePath != n.relativePath && old.hash == n.hash }

            // prefer the file with equal timestamp meta, otherwise fall back to just equal hash
            equalMeta().orElse(equalHash()).map { n => FileMoved(n.hash, old.relativePath, n.relativePath)}

          }.toList

        newResources.foreach(e => logger.info(s"new file: ${e.file.relativePath}"))
        deletedResources.foreach(e => logger.info(s"deleted file: ${e.relativePath}"))
        movedResources.foreach(e => logger.info(s"moved file: ${e.oldPath} -> ${e.newPath}"))

        Effect
          .persist(newResources ::: deletedResources ::: movedResources)
          .thenReply[Set[LocalFile]](sender)(state => state)

      case DeleteFileByHash(hash, sender) =>

        val deleted = state.find(_.hash == hash).map { file =>

          val path = config.mediaPath.resolve(file.relativePath)

          if (Files.exists(path)) {
            config.deleteMedia match {
              case DeleteFile  => Files.delete(path)
              case MoveToTrash => Desktop.getDesktop().moveToTrash(path.toFile())
            }
          };

          FileDeleted(hash, file.relativePath)
        }

        deleted match {
          case Some(e) => Effect.persist(e).thenReply(sender)(_ => true)
          case None    => Effect.reply(sender)(false)
        }

      case UploadCompleted(relativePath, hash, originalSender) =>

        val path = Path.of(relativePath)

        val event = FileAdded(LocalFile(relativePath, hash, path.size, path.creationTimeMillis(), path.lastModifiedMillis()))

        Effect.persist(event).thenReply(originalSender)(_ => true)

      case Upload(fileName, sourceRef, sender) =>
        logger.info(s"Processing upload request: $fileName")

        val path = config.uploadPath.resolve(fileName)

        Files.createDirectories(config.uploadPath)
        Files.createFile(path)

        val hashSink = {
          import java.security.MessageDigest
          Sink.fold[MessageDigest, ByteString](MessageDigest.getInstance(config.hashingAlgorithm.algorithm)) {
            case (digest, bytes) => digest.update(bytes.asByteBuffer); digest
          }
        }

        val toPathSink = FileIO.toPath(path)

        val (hashF, ioF) = GraphShapes.broadcast(sourceRef.source, hashSink, toPathSink).run()

        val futureResult = for (
          hash     <- hashF;
          ioResult <- ioF
        ) yield (config.hashingAlgorithm.encodeHash(hash.digest()), ioResult)

        futureResult
          .flatMap { case (hash, ioResult) =>
            ioResult.status match {
              case Success(_) => Future.successful(hash)
              case Failure(t) => Future.failed(t)
            }
          }.onComplete {
            case Success(hash) =>
              logger.info(s"Upload successful")
              context.self ! UploadCompleted(config.relativeUploadPath.resolve(fileName).toString, hash, sender)
            case Failure(t) =>
              logger.warn(s"Upload failed", t)
              Files.delete(path)
              FastFuture.failed(t)
              sender ! false
          }

        Effect.none
    }
  }
}
