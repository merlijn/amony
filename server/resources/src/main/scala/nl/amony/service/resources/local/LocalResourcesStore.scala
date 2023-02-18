package nl.amony.service.resources.local

import akka.NotUsed
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.util.FastFuture
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, SourceRef, SystemMaterializer}
import akka.util.ByteString
import cats.effect.unsafe.IORuntime
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceConfig.{DeleteFile, LocalResourcesConfig, MoveToTrash}
import nl.amony.service.resources.local.DirectoryScanner.{LocalFile, ResourceAdded, ResourceDeleted, ResourceEvent, ResourceMoved}
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

  def broadcast[T, A, B](s: Source[T, NotUsed], a: Sink[T, A], b: Sink[T, B]): RunnableGraph[(A, B)] =
    RunnableGraph.fromGraph(GraphDSL.createGraph(a, b)((_, _)) { implicit builder =>
      (a, b) =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[T](2))
        s ~> broadcast.in
        broadcast ~> a.in
        broadcast ~> b.in
        ClosedShape
    })


  case class GetByHash(hash: String, sender: ActorRef[Option[LocalFile]]) extends LocalResourceCommand
  case class GetAll(sender: ActorRef[Set[LocalFile]]) extends LocalResourceCommand
  case class FullScan(sender: ActorRef[Set[LocalFile]]) extends LocalResourceCommand
  case class Upload(fileName: String, source: SourceRef[ByteString], sender: ActorRef[Boolean]) extends LocalResourceCommand
  case class DeleteFileByHash(hash: String, sender: ActorRef[Boolean]) extends LocalResourceCommand

  private case class UploadCompleted(relativePath: String, hash: String, originalSender: ActorRef[Boolean]) extends LocalResourceCommand

  private def setupAndRegister[T: ServiceKey](factory: ActorContext[T] => Behavior[T]): Behavior[T] =
    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(implicitly[ServiceKey[T]], context.self)
      factory(context)
    }

  def behavior(config: LocalResourcesConfig): Behavior[LocalResourceCommand] =
    setupAndRegister[LocalResourceCommand] { context =>

      implicit val ec = context.executionContext
      implicit val sc = context.system.scheduler

      EventSourcedBehavior[LocalResourceCommand, ResourceEvent, Set[LocalFile]](
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


  def eventSource(state: Set[LocalFile], e: ResourceEvent): Set[LocalFile] = e match {
    case ResourceAdded(resource) =>
      state + resource
    case ResourceDeleted(hash, relativePath) =>
      state -- state.find(r => r.relativePath == relativePath && r.hash == hash)
    case ResourceMoved(hash, oldPath, newPath) =>
      val old = state.find(r => r.relativePath == oldPath && r.hash == hash)
      state -- old ++ old.map(_.copy(relativePath = newPath))
  }

  def commandHandler(config: LocalResourcesConfig, context: ActorContext[LocalResourceCommand])(state: Set[LocalFile], cmd: LocalResourceCommand): Effect[ResourceEvent, Set[LocalFile]] = {

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

          ResourceDeleted(hash, file.relativePath)
        }

        deleted match {
          case Some(e) => Effect.persist(e).thenReply(sender)(_ => true)
          case None    => Effect.reply(sender)(false)
        }

      case UploadCompleted(relativePath, hash, originalSender) =>

        val path = Path.of(relativePath)

        val event = ResourceAdded(LocalFile(relativePath, hash, path.size, path.creationTimeMillis(), path.lastModifiedMillis()))

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

        val (hashF, ioF) = broadcast(sourceRef.source, hashSink, toPathSink).run()

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
