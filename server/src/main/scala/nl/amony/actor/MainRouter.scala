package nl.amony.actor

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import nl.amony.AmonyConfig
import nl.amony.actor.index.InMemoryIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.{MediaApi, MediaLibProtocol}
import nl.amony.actor.resources.ResourcesProtocol.ResourceCommand
import nl.amony.actor.resources.{MediaScanner, ResourceApi}
import nl.amony.user.UserApi

trait Message

object MainRouter {

  def apply(config: AmonyConfig, scanner: MediaScanner): Behavior[Message] =
    Behaviors.setup { context =>

      implicit val mat = Materializer(context)

      val localIndexRef = InMemoryIndex.apply(config.media, context).toTyped[QueryMessage]
      val resourceRef   = context.spawn(ResourceApi.resourceBehaviour(config.media, scanner), "resources")
      val mediaRef      = context.spawn(MediaApi.mediaBehaviour(config.media, resourceRef), "medialib")
      val userRef       = context.spawn(UserApi.userBehaviour(), "users")

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
