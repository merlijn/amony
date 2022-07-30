package nl.amony.service.media

import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.JacksonObjectMapperProvider
import com.fasterxml.jackson.core.JsonEncoding
import nl.amony.lib.akka.{AkkaServiceModule, ServiceBehaviors}
import nl.amony.service.media.actor.MediaLibEventSourcing.Event
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.service.media.actor.{MediaLibCommandHandler, MediaLibEventSourcing}
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future

object MediaService {

  val mediaPersistenceId = "mediaLib"

  def behavior(): Behavior[MediaCommand] =
    ServiceBehaviors.setupAndRegister[MediaCommand] { context =>

      implicit val ec = context.executionContext
      implicit val sc = context.system.scheduler

      EventSourcedBehavior[MediaCommand, Event, State](
        persistenceId  = PersistenceId.ofUniqueId(mediaPersistenceId),
        emptyState     = State(Map.empty),
        commandHandler = MediaLibCommandHandler(),
        eventHandler   = MediaLibEventSourcing.apply
      )
    }
}

class MediaService(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) with Logging {

  def getById(id: String): Future[Option[Media]] =
    ask[MediaCommand, Option[Media]](ref => GetById(id, ref))

  def getAll(): Future[List[Media]] =
    ask[MediaCommand, List[Media]](ref => GetAll(ref))

  def exportToJson(): Future[String] = {

    val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)

    getAll().map { medias =>
      val out = new ByteArrayOutputStream()
      objectMapper.createGenerator(out, JsonEncoding.UTF8).useDefaultPrettyPrinter().writeObject(medias)
      out.toString()
    }
  }

  def upsertMedia(media: Media): Future[Media] =
    ask[MediaCommand, Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

  def deleteMedia(id: String, deleteResource: Boolean): Future[Boolean] =
    ask[MediaCommand, Boolean](ref => RemoveMedia(id, deleteResource, ref))

  def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String]): Future[Either[ErrorResponse, Media]] =
    ask[MediaCommand, Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))
}
