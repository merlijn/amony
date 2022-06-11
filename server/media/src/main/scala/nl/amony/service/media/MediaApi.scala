package nl.amony.service.media

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import nl.amony.lib.akka.{AkkaServiceModule, ServiceBehaviors}
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.actor.MediaLibEventSourcing.Event
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.service.media.actor.{MediaLibCommandHandler, MediaLibEventSourcing}
import nl.amony.service.resources.ResourceProtocol.ResourceCommand
import scribe.Logging

import scala.concurrent.Future

object MediaApi {

  val mediaPersistenceId = "mediaLib"

  def behavior(config: LocalResourcesConfig, resourceRef: ActorRef[ResourceCommand]): Behavior[MediaCommand] =
    ServiceBehaviors.setupAndRegister[MediaCommand] { context =>

      implicit val ec = context.executionContext
      implicit val sc = context.system.scheduler

      EventSourcedBehavior[MediaCommand, Event, State](
        persistenceId  = PersistenceId.ofUniqueId(mediaPersistenceId),
        emptyState     = State(Map.empty),
        commandHandler = MediaLibCommandHandler(config, resourceRef),
        eventHandler   = MediaLibEventSourcing.apply
      )
    }
}

class MediaApi(system: ActorSystem[Nothing])
  extends AkkaServiceModule[MediaCommand](system) with Logging {

  def getById(id: String): Future[Option[Media]] =
    askService[Option[Media]](ref => GetById(id, ref))

  def getAll(): Future[List[Media]] =
    askService[List[Media]](ref => GetAll(ref))

  def upsertMedia(media: Media): Future[Media] =
    askService[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

  def deleteMedia(id: String, deleteResource: Boolean): Future[Boolean] =
    askService[Boolean](ref => RemoveMedia(id, deleteResource, ref))

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String]): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))

  def addFragment(mediaId: String, from: Long, to: Long): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => AddFragment(mediaId, from, to, ref))

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => UpdateFragmentRange(mediaId, idx, from, to, ref))

  def updateFragmentTags(id: String, idx: Int, tags: List[String]): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => UpdateFragmentTags(id, idx, tags, ref))

  def deleteFragment(id: String, idx: Int): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref))
}
