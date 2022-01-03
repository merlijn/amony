package nl.amony.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibProtocol.Command
import akka.actor.typed.scaladsl.adapter._
import nl.amony.actor.index.LocalIndex
import nl.amony.actor.index.QueryProtocol._
import nl.amony.lib.MediaScanner

trait Message

object MainRouter {

  def apply(config: MediaLibConfig, scanner: MediaScanner): Behavior[Message] =
    Behaviors.setup { context =>
      implicit val mat = Materializer(context)

      val cmdHandler: ActorRef[Command] = context.spawn(MediaLibProtocol.apply(config, scanner), "medialib")
      val localIndex = LocalIndex.apply(config, context, cmdHandler.toClassic).toTyped[QueryMessage]

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
