package nl.amony.lib.akka

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import scribe.Logging

import scala.reflect.ClassTag

object ServiceBehaviors extends Logging {

  def forwardToService[T : ClassTag](serviceKey: ServiceKey[T]): Behavior[T] = {

    def forwardToServiceInternal(listings: Set[ActorRef[T]]): Behavior[Either[Receptionist.Listing, T]] = {

      Behaviors.receiveMessage[Either[Receptionist.Listing, T]] {
        case Left(listings) =>
          forwardToServiceInternal(listings.serviceInstances(serviceKey))
        case Right(msg) =>
          if (!listings.isEmpty)
            listings.head.tell(msg)
          else
            logger.info(s"${serviceKey.id} not yet started, dropped message of type '${msg.getClass}' ")

          Behaviors.same
      }
    }

    Behaviors.setup[Either[Receptionist.Listing, T]] { context =>

      val listingsAdapter = context.messageAdapter[Receptionist.Listing](listings => Left(listings))
      context.system.receptionist ! Receptionist.Subscribe(serviceKey, listingsAdapter)

      forwardToServiceInternal(Set.empty)
    }.transformMessages[T](Right(_))
  }

  def withRegistration[T: ServiceKey](factory: => Behavior[T]): Behavior[T] =
    setupAndRegister[T](_ => factory)

  def setupAndRegister[T : ServiceKey](factory: ActorContext[T] => Behavior[T]): Behavior[T] =
    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(implicitly[ServiceKey[T]], context.self)
      factory(context)
    }
}
