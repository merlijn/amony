//package nl.amony.lib.akka
//
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
//import akka.actor.typed.scaladsl.Behaviors
//
//import scala.reflect.ClassTag
//
//object ServiceTracker {
//
//  def forward[T](listings: Set[ActorRef[T]]): Behavior[T] = {
//
//    Behaviors.receiveMessagePartial[T] {
//      case msg =>
//        listings.head.tell(msg)
//        Behaviors.same
//    }
//  }
//
//  def apply[T : ClassTag](serviceKey: ServiceKey[T]): Behavior[T] = {
//    Behaviors.setup { context =>
//
//      context.system.receptionist ! Receptionist.Subscribe(serviceKey, context.self)
//
//      Behaviors.receiveMessagePartial[Any] {
//        case serviceKey.Listing(listings) =>
//
//          forward[T](listings)
//      }
//    }
//  }
//}
