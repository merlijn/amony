package nl.amony.actor.user

import akka.actor.typed.ActorRef

object UserProtocol {

  sealed trait UserCommand

  case class User(id: String, email: String, passwordHash: String)
  case class UpsertUser(email: String, password: String, sender: ActorRef[Boolean]) extends UserCommand
  case class Authenticate(email: String, password: String) extends UserCommand
}
