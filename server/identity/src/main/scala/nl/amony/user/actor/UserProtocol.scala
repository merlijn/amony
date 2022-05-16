package nl.amony.user.actor

import akka.actor.typed.ActorRef

object UserProtocol {

  sealed trait UserCommand

  case class User(id: String, email: String, passwordHash: String)
  case class UpsertUser(email: String, password: String, sender: ActorRef[User])                     extends UserCommand
  case class Authenticate(email: String, password: String, sender: ActorRef[AuthenticationResponse]) extends UserCommand

  sealed trait AuthenticationResponse
  case class Authentication(userId: String) extends AuthenticationResponse
  case object InvalidCredentials            extends AuthenticationResponse
}
