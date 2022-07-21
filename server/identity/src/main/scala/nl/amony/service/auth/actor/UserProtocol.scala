package nl.amony.service.auth.actor

import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.ServiceKey

object UserProtocol {

  sealed trait UserCommand

  object UserCommand {
    implicit val serviceKey: ServiceKey[UserCommand] = ServiceKey[UserCommand]("auth-service")
  }

  case class User(id: String, email: String, passwordHash: String)
  case class UpsertUser(email: String, password: String, sender: ActorRef[User])                     extends UserCommand
  case class Authenticate(email: String, password: String, sender: ActorRef[AuthenticationResponse]) extends UserCommand

  sealed trait AuthenticationResponse
  case class Authentication(userId: String, token: String) extends AuthenticationResponse
  case object InvalidCredentials            extends AuthenticationResponse
}
