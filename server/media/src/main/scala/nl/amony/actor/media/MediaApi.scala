package nl.amony.actor.media

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.Timeout
import nl.amony.actor.media.MediaApi.mediaServiceKey
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.actor.media.MediaLibEventSourcing.Event
import nl.amony.actor.media.MediaLibProtocol._
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand
import nl.amony.lib.akka.AkkaServiceModule
import scribe.Logging

import scala.concurrent.Future

object MediaApi {
  def mediaBehaviour(config: MediaLibConfig, resourceRef: ActorRef[ResourceCommand]): Behavior[MediaCommand] =
    Behaviors.setup[MediaCommand] { context =>
      context.system.receptionist ! Receptionist.Register(mediaServiceKey, context.self)

      implicit val ec = context.executionContext
      implicit val sc = context.system.scheduler

      EventSourcedBehavior[MediaCommand, Event, State](
        persistenceId  = PersistenceId.ofUniqueId("mediaLib"),
        emptyState     = State(Map.empty),
        commandHandler = MediaLibCommandHandler(config, resourceRef),
        eventHandler   = MediaLibEventSourcing.apply
      )
    }

  val mediaServiceKey = ServiceKey[MediaCommand]("mediaService")

  val mediaPersistenceId = "mediaLib"
}

class MediaApi(override val system: ActorSystem[Nothing], override implicit val askTimeout: Timeout) extends AkkaServiceModule[MediaCommand] with Logging {

  override val serviceKey: ServiceKey[MediaCommand] = mediaServiceKey

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

  def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
    askService[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref))
}
