package nl.amony.actor

import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.stream.Materializer
import nl.amony.AmonyConfig
import nl.amony.actor.index.InMemoryIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.MediaApi
import nl.amony.actor.resources.{MediaScanner, ResourceApi}
import nl.amony.api.SearchApi.searchServiceKey
import nl.amony.user.UserApi

object MainRouter {

  def apply(config: AmonyConfig, scanner: MediaScanner): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>

      implicit val mat = Materializer(context)

      val localIndexRef = InMemoryIndex.apply(config.media, context).toTyped[QueryMessage]
      context.system.receptionist ! Receptionist.Register(searchServiceKey, localIndexRef)

      val resourceRef   = context.spawn(ResourceApi.resourceBehaviour(config.media, scanner), "resources")
      val mediaRef      = context.spawn(MediaApi.mediaBehaviour(config.media, resourceRef), "medialib")
      val userRef       = context.spawn(UserApi.userBehaviour(), "users")

      Behaviors.empty
    }
}
