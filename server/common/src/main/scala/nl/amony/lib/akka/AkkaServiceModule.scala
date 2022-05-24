package nl.amony.lib.akka

import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait AkkaServiceModule[T] {

  def system: ActorSystem[Nothing]
  def serviceKey: ServiceKey[T]
  implicit def askTimeout: Timeout

  lazy implicit val ec: ExecutionContext = system.executionContext
  lazy implicit val mat: Materializer    = SystemMaterializer.get(system).materializer
  lazy implicit val scheduler: Scheduler = system.scheduler

  def askService[Res](replyTo: ActorRef[Res] => T) = serviceRef().flatMap(_.ask(replyTo))

  def loadConfig[T : ClassTag](path: String)(implicit reader: ConfigReader[T]): T = {

    val configSource = ConfigSource.fromConfig(system.settings.config.getConfig(path))
    val config = configSource.loadOrThrow[T]

    config
  }

  def serviceRef()(implicit timeout: Timeout): Future[ActorRef[T]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(serviceKey, ref))(timeout, system.scheduler)
      .map(_.serviceInstances(serviceKey).head)
}
