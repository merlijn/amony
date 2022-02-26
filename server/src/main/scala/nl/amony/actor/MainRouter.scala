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
import nl.amony.actor.resources.{LocalResourcesHandler, MediaScanner}
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand
import nl.amony.actor.user.{UserCommandHandler, UserEventSourcing}
import nl.amony.actor.user.UserCommandHandler.UserState
import nl.amony.actor.user.UserEventSourcing.UserEvent
import nl.amony.actor.user.UserProtocol.{UpsertUser, UserCommand}

trait Message

object MainRouter {

  private[actor] def userBehaviour(): EventSourcedBehavior[UserCommand, UserEvent, UserState] =
    EventSourcedBehavior[UserCommand, UserEvent, UserState](
      persistenceId  = PersistenceId.ofUniqueId("users"),
      emptyState     = UserState(Map.empty),
      commandHandler = UserCommandHandler.apply(str => str),
      eventHandler   = UserEventSourcing.apply
    )

  private[actor] def mediaBehaviour(config: MediaLibConfig, resourceRef: ActorRef[ResourceCommand]): Behavior[MediaCommand] =
    Behaviors.setup[MediaCommand] { context =>
      EventSourcedBehavior[MediaCommand, Event, State](
        persistenceId = PersistenceId.ofUniqueId("mediaLib"),
        emptyState = State(Map.empty),
        commandHandler = MediaLibCommandHandler(context, config, resourceRef),
        eventHandler = MediaLibEventSourcing.apply
      )
    }

  private[actor] def resourceBehaviour(config: MediaLibConfig, scanner: MediaScanner): Behavior[ResourceCommand] =
    LocalResourcesHandler.apply(config, scanner)

  def apply(config: AmonyConfig, scanner: MediaScanner): Behavior[Message] =
    Behaviors.setup { context =>

      implicit val mat = Materializer(context)

      val localIndexRef = LocalIndex.apply(config.media, context).toTyped[QueryMessage]
      val resourceRef   = context.spawn(resourceBehaviour(config.media, scanner), "resources")
      val mediaRef      = context.spawn(mediaBehaviour(config.media, resourceRef), "medialib")
      val userRef       = context.spawn(userBehaviour(), "users")

      // insert the admin user on startup
      userRef.tell(UpsertUser(config.users.adminUsername, config.users.adminPassword, context.system.ignoreRef))

      Behaviors.receiveMessage[Message] {

        case q: QueryMessage =>
          localIndexRef.tell(q)
          Behaviors.same
        case cmd: MediaLibProtocol.MediaCommand =>
          mediaRef.tell(cmd)
          Behaviors.same
        case cmd: UserCommand =>
          userRef.tell(cmd)
          Behaviors.same
        case cmd: ResourceCommand =>
          resourceRef.tell(cmd)
          Behaviors.same
      }
    }
}
