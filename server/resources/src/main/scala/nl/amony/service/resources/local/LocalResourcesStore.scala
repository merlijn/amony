package nl.amony.service.resources.local

import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.util.FastFuture
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.stream.scaladsl.{FileIO, Sink}
import akka.stream.{SourceRef, SystemMaterializer}
import akka.util.ByteString
import cats.effect.unsafe.IORuntime
import nl.amony.lib.akka.{GraphShapes, ServiceBehaviors}
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceConfig.{DeleteFile, LocalResourcesConfig, MoveToTrash}
import nl.amony.service.resources.local.DirectoryScanner.{FileAdded, FileDeleted, FileMoved, LocalFile, LocalResourceEvent}
import scribe.Logging

import java.awt.Desktop
import java.nio.file.{Files, Path}
import scala.concurrent.Future
import scala.util.{Failure, Success}

object LocalResourcesStore extends Logging {

  def persistenceId(directoryId: String) = s"local-resources-$directoryId"

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

    implicit val runtime = IORuntime.global
    implicit val mat = SystemMaterializer.get(context.system).materializer
    implicit val ec = context.executionContext

    cmd match {

      case GetAll(sender) =>
        Effect.reply(sender)(state)

      case GetByHash(hash, sender) =>
        Effect.reply(sender)(state.find(_.hash == hash))

      case FullScan(sender) =>

        val events = DirectoryScanner.diff(config, state)

        Effect
          .persist(events)
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
