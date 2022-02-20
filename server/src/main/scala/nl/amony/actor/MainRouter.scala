package nl.amony.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import nl.amony.{AmonyConfig, AppConfig, MediaLibConfig}
import nl.amony.actor.media.MediaLibProtocol.{MediaCommand, State}
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import nl.amony.actor.index.LocalIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.MediaLibEventSourcing.Event
import nl.amony.actor.media.{MediaLibCommandHandler, MediaLibEventSourcing, MediaLibProtocol}
import nl.amony.actor.resources.LocalFileResourceHandler
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand
import nl.amony.actor.user.{UserCommandHandler, UserEventSourcing}
import nl.amony.actor.user.UserCommandHandler.UserState
import nl.amony.actor.user.UserEventSourcing.UserEvent
import nl.amony.actor.user.UserProtocol.{UpsertUser, UserCommand}
import nl.amony.tasks.MediaScanner

trait Message

object MainRouter {

  private[actor] def userBehaviour(): EventSourcedBehavior[UserCommand, UserEvent, UserState] =
    EventSourcedBehavior[UserCommand, UserEvent, UserState](
      persistenceId  = PersistenceId.ofUniqueId("users"),
      emptyState     = UserState(Map.empty),
      commandHandler = UserCommandHandler.apply(str => str),
      eventHandler   = UserEventSourcing.apply
    )

  private[actor] def mediaBehaviour(config: MediaLibConfig, scanner: MediaScanner): EventSourcedBehavior[MediaCommand, Event, State] =
    EventSourcedBehavior[MediaCommand, Event, State](
      persistenceId  = PersistenceId.ofUniqueId("mediaLib"),
      emptyState     = State(Map.empty),
      commandHandler = MediaLibCommandHandler.apply(config, scanner),
      eventHandler   = MediaLibEventSourcing.apply
    )

  private[actor] def resourceBehaviour(config: MediaLibConfig): Behavior[ResourceCommand] =
    LocalFileResourceHandler.apply(config)

  def apply(config: AmonyConfig, scanner: MediaScanner): Behavior[Message] =
    Behaviors.setup { context =>

      implicit val mat = Materializer(context)

      val localIndex   = LocalIndex.apply(config.media, context).toTyped[QueryMessage]
      val mediaHandler = context.spawn(mediaBehaviour(config.media, scanner), "medialib")
      val userHandler  = context.spawn(userBehaviour(), "users")
      val resourceHandler = context.spawn(resourceBehaviour(config.media), "resources")

      // insert the admin user on startup
      userHandler.tell(UpsertUser(config.users.adminUsername, config.users.adminPassword, context.system.ignoreRef))

      Behaviors.receiveMessage[Message] {

        case q: QueryMessage =>
          localIndex.tell(q)
          Behaviors.same
        case cmd: MediaLibProtocol.MediaCommand =>
          mediaHandler.tell(cmd)
          Behaviors.same
        case cmd: UserCommand =>
          userHandler.tell(cmd)
          Behaviors.same
        case cmd: ResourceCommand =>
          resourceHandler.tell(cmd)
          Behaviors.same
      }
    }
}
