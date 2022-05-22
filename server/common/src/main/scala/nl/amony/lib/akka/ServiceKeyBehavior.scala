package nl.amony.lib.akka

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors

object ServiceKeyBehavior {

  private def innerForward[T](serviceKey: ServiceKey[T], listings: Set[ActorRef[T]], subscribe: Boolean): Behavior[Either[Receptionist.Listing, T]] = {

    Behaviors.setup { context =>

      if (subscribe) {
        val listingsAdaper = context.messageAdapter[Receptionist.Listing](listings => Left(listings))
        context.system.receptionist ! Receptionist.Subscribe(serviceKey, listingsAdaper)
      }

      Behaviors.receiveMessage[Either[Receptionist.Listing, T]] {
        case Left(listings) =>
          innerForward[T](serviceKey, listings.serviceInstances(serviceKey), false)
        case Right(msg) =>
          if (!listings.isEmpty)
            listings.head.tell(msg)

          Behaviors.same
      }
    }
  }

  def apply[T](serviceKey: ServiceKey[T]): Behavior[T] = {
    Behaviors.setup { context =>
      val forwarder = context.spawn(innerForward(serviceKey, Set.empty[ActorRef[T]], true), "forwarder")
      Behaviors.receiveMessage[T] {
        case msg =>
          forwarder.tell(Right(msg))
          Behaviors.same
      }
    }
  }
}
