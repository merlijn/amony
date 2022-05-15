package nl.amony.actor.media

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.Timeout
import nl.amony.actor.media.MediaApi.mediaServiceKey
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.actor.media.MediaLibEventSourcing.Event
import nl.amony.actor.media.MediaLibProtocol._
import nl.amony.actor.resources.ResourcesProtocol
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand

import scala.concurrent.{ExecutionContext, Future}

object MediaApi {
  def mediaBehaviour(config: MediaLibConfig, resourceRef: ActorRef[ResourceCommand]): Behavior[MediaCommand] =
    Behaviors.setup[MediaCommand] { context =>

      context.system.receptionist ! Receptionist.Register(mediaServiceKey, context.self)

      EventSourcedBehavior[MediaCommand, Event, State](
        persistenceId = PersistenceId.ofUniqueId("mediaLib"),
        emptyState = State(Map.empty),
        commandHandler = MediaLibCommandHandler(context, config, resourceRef),
        eventHandler = MediaLibEventSourcing.apply
      )
    }

  val mediaServiceKey = ServiceKey[MediaCommand]("mediaService")
}

class MediaApi(system: ActorSystem[Nothing]) {

  implicit val scheduler            = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  private def mediaRef()(implicit timeout: Timeout): Future[ActorRef[MediaCommand]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(mediaServiceKey, ref))(timeout, system.scheduler)
      .map( _.serviceInstances(mediaServiceKey).head)

  def getById(id: String)(implicit timeout: Timeout): Future[Option[Media]] =
    mediaRef().flatMap(_.ask[Option[Media]](ref => GetById(id, ref)))

  def getAll()(implicit timeout: Timeout): Future[List[Media]] =
    mediaRef().flatMap(_.ask[List[Media]](ref => GetAll(ref)))

  def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] =
    mediaRef().flatMap(_.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media))

  def deleteMedia(id: String, deleteResource: Boolean)(implicit timeout: Timeout): Future[Boolean] =
    mediaRef().flatMap(_.ask[Boolean](ref => RemoveMedia(id, deleteResource, ref)))

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    mediaRef().flatMap(_.ask[Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref)))

  def addFragment(mediaId: String, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    mediaRef().flatMap(_.ask[Either[ErrorResponse, Media]](ref => AddFragment(mediaId, from, to, ref)))

  def updateFragmentRange(mediaId: String, idx: Int, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    mediaRef().flatMap(_.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentRange(mediaId, idx, from, to, ref)))

  def updateFragmentTags(id: String, idx: Int, tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    mediaRef().flatMap(_.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentTags(id, idx, tags, ref)))

  def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    mediaRef().flatMap(_.ask[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref)))
}
