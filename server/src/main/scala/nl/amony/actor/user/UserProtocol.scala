package nl.amony.actor.user

import akka.actor.typed.ActorRef
import nl.amony.actor.Message

object UserProtocol {

  sealed trait UserCommand extends Message

  case class User(id: String, email: String, passwordHash: String)
  case class UpsertUser(email: String, password: String, sender: ActorRef[Boolean]) extends UserCommand
  case class Authenticate(email: String, password: String, sender: ActorRef[Boolean]) extends UserCommand
}
