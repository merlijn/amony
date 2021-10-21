package nl.amony.actor

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibProtocol.Command
import akka.actor.typed.scaladsl.adapter._
import nl.amony.actor.MediaIndex.IndexQuery

object MainRouter {

  def apply(config: MediaLibConfig): Behavior[Any] =

    Behaviors.setup { context =>

      implicit val mat       = Materializer(context)

      val localIndex = MediaIndex.apply(config, context).toTyped[IndexQuery]
      val handler = context.spawn(MediaLibProtocol.apply(config), "medialib")

      Behaviors.receiveMessage {

        case q: IndexQuery =>
          localIndex.tell(q)
          Behaviors.same
        case cmd: Command =>
          handler.tell(cmd)
          Behaviors.same
      }
    }
}
