package nl.amony.actor.user

import akka.actor.typed.ActorRef

object UserProtocol {

  sealed trait UserCommand

  case class UpsertUser(email: String, password: String, sender: ActorRef[Boolean]) extends UserCommand
}
