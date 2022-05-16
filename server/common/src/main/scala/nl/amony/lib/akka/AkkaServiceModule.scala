package nl.amony.lib.akka

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

trait AkkaServiceModule[T] {

  def system: ActorSystem[Nothing]
  def serviceKey: ServiceKey[T]

  lazy implicit val ec: ExecutionContext = system.executionContext
  lazy implicit val mat: Materializer    = SystemMaterializer.get(system).materializer
  lazy implicit val scheduler            = system.scheduler

  def askService[Res](replyTo: ActorRef[Res] => T)(implicit timeout: Timeout) = serviceRef().flatMap(_.ask(replyTo))

  def serviceRef()(implicit timeout: Timeout): Future[ActorRef[T]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(serviceKey, ref))(timeout, system.scheduler)
      .map(_.serviceInstances(serviceKey).head)
}
