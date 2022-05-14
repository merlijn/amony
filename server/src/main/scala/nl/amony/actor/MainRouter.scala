package nl.amony.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.stream.Materializer
import nl.amony.actor.index.InMemoryIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.MediaLibEventSourcing.Event
import nl.amony.actor.media.MediaLibProtocol.{MediaCommand, State}
import nl.amony.actor.media.{MediaLibCommandHandler, MediaLibEventSourcing, MediaLibProtocol}
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand
import nl.amony.actor.resources.{LocalResourcesHandler, MediaScanner}
import nl.amony.user.UserApi
import nl.amony.user.actor.UserProtocol.{UpsertUser, UserCommand}
import nl.amony.{AmonyConfig, MediaLibConfig}

trait Message

object MainRouter {

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

      val localIndexRef = InMemoryIndex.apply(config.media, context).toTyped[QueryMessage]
      val resourceRef   = context.spawn(resourceBehaviour(config.media, scanner), "resources")
      val mediaRef      = context.spawn(mediaBehaviour(config.media, resourceRef), "medialib")
      val userRef       = context.spawn(UserApi.userBehaviour(), "users")

      // insert the admin user on startup
      userRef.tell(UpsertUser(config.users.adminUsername, config.users.adminPassword, context.system.ignoreRef))

      Behaviors.receiveMessage[Message] {

        case q: QueryMessage =>
          localIndexRef.tell(q)
          Behaviors.same
        case cmd: MediaLibProtocol.MediaCommand =>
          mediaRef.tell(cmd)
          Behaviors.same
        case cmd: ResourceCommand =>
          resourceRef.tell(cmd)
          Behaviors.same
      }
    }
}
