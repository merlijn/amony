package nl.amony.lib.akka

import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

abstract class AkkaServiceModule[T : ServiceKey](val system: ActorSystem[Nothing]) {

  implicit def askTimeout: Timeout = Timeout(5.seconds)

  implicit val ec: ExecutionContext = system.executionContext
  implicit val mat: Materializer    = SystemMaterializer.get(system).materializer
  implicit val scheduler: Scheduler = system.scheduler

  def askService[Res](replyTo: ActorRef[Res] => T) = serviceRef().flatMap(_.ask(replyTo))

  def loadConfig[T : ClassTag](path: String)(implicit reader: ConfigReader[T]): T = {

    val configSource = ConfigSource.fromConfig(system.settings.config.getConfig(path))
    val config = configSource.loadOrThrow[T]

    config
  }

  def serviceRef(): Future[ActorRef[T]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(implicitly[ServiceKey[T]], ref))
      .map(_.serviceInstances(implicitly[ServiceKey[T]]).head)
}
