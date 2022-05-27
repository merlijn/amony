package nl.amony.lib.akka

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import scribe.Logging

import scala.reflect.ClassTag

object ServiceKeyBehavior extends Logging {

  def apply[T : ClassTag](serviceKey: ServiceKey[T]): Behavior[T] =
    forward[T](serviceKey, Set.empty, true).transformMessages[T](Right(_))

  private def forward[T : ClassTag](serviceKey: ServiceKey[T], listings: Set[ActorRef[T]], subscribe: Boolean): Behavior[Either[Receptionist.Listing, T]] = {

    Behaviors.setup[Either[Receptionist.Listing, T]] { context =>

      if (subscribe) {
        val listingsAdaper = context.messageAdapter[Receptionist.Listing](listings => Left(listings))
        context.system.receptionist ! Receptionist.Subscribe(serviceKey, listingsAdaper)
      }

      Behaviors.receiveMessage[Either[Receptionist.Listing, T]] {
        case Left(listings) =>
          forward[T](serviceKey, listings.serviceInstances(serviceKey), false)
        case Right(msg) =>
          if (!listings.isEmpty) {
            listings.head.tell(msg)
          } else
            logger.info(s"${serviceKey.id} not yet started, dropped message of type '${msg.getClass}' ")

          Behaviors.same
      }
    }
  }
}
