package nl.amony.actor

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import nl.amony.MediaLibConfig
import nl.amony.actor.media.MediaLibProtocol.Command
import akka.actor.typed.scaladsl.adapter._
import nl.amony.actor.index.LocalIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.media.MediaLibProtocol
import nl.amony.lib.MediaScanner

trait Message

object MainRouter {

  def apply(config: MediaLibConfig, scanner: MediaScanner): Behavior[Message] =
    Behaviors.setup { context =>
      implicit val mat = Materializer(context)

      val localIndex = LocalIndex.apply(config, context).toTyped[QueryMessage]
      val cmdHandler    = context.spawn(MediaLibProtocol.apply(config, scanner), "medialib")

      Behaviors.receiveMessage[Message] {

        case q: QueryMessage =>
          localIndex.tell(q)
          Behaviors.same
        case cmd: Command =>
          cmdHandler.tell(cmd)
          Behaviors.same
      }
    }
}
