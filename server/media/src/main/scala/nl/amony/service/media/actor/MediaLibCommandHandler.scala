package nl.amony.service.media.actor

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import nl.amony.service.media.MediaConfig.FragmentSettings
import nl.amony.service.media.actor.MediaLibEventSourcing._
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.service.resources.ResourceProtocol
import nl.amony.service.resources.ResourceProtocol.{DeleteResource, ResourceCommand}
import scribe.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object MediaLibCommandHandler extends Logging {

  def apply(fragmentSettings: FragmentSettings, resourceRef: ActorRef[ResourceCommand])(
      state: State,
      cmd: MediaCommand
  )(implicit ec: ExecutionContext, scheduler: Scheduler): Effect[Event, State] = {

    logger.debug(s"Received command: $cmd")

    implicit val askTimeout: Timeout = Timeout(5.seconds)

    def requireMedia[T](mediaId: String, sender: ActorRef[Either[ErrorResponse, T]])(
        effect: Media => Effect[Event, State]
    ): Effect[Event, State] = {
      state.media.get(mediaId) match {
        case None        => Effect.reply(sender)(Left(MediaNotFound(mediaId)))
        case Some(media) => effect(media)
      }
    }

    cmd match {

      case UpsertMedia(media, sender) =>

        if (state.media.get(media.id) == Some(media))
          Effect.none
        else
          Effect
            .persist(MediaAdded(media))
            .thenRun((_: State) => resourceRef.tell(ResourceProtocol.CreateFragments(media, false)))
            .thenReply(sender)(_ => true)

      case UpdateMetaData(mediaId, title, comment, tags, sender) =>
        requireMedia(mediaId, sender) { media =>
          val titleUpdate   = if (title == media.meta.title) None else title
          val commentUpdate = if (comment == media.meta.comment) None else comment
          val tagsAdded     = tags -- media.meta.tags
          val tagsRemoved   = media.meta.tags -- tags

          if (tagsAdded.isEmpty && tagsRemoved.isEmpty && titleUpdate.isEmpty && commentUpdate.isEmpty)
            Effect.reply(sender)(Right(media))
          else
            Effect
              .persist(MediaMetaDataUpdated(mediaId, titleUpdate, commentUpdate, tagsAdded, tagsRemoved))
              .thenReply(sender)(s => Right(s.media(mediaId)))
        }

      case RemoveMedia(mediaId, deleteFile, sender) =>
        val media = state.media(mediaId)

        logger.info(s"Deleting media '$mediaId - ${media.resourceInfo.relativePath}'")

        Effect
          .persist(MediaRemoved(mediaId))
          .thenRun((s: State) => if (deleteFile) resourceRef.tell(DeleteResource(media.resourceInfo.hash, akka.actor.ActorRef.noSender.toTyped[Boolean])))
          .thenReply(sender)(_ => true)

      case GetById(id, sender) =>
        Effect.reply(sender)(state.media.get(id))

      case GetAll(sender) =>
        Effect.reply(sender)(state.media.values.toList)
    }
  }
}
