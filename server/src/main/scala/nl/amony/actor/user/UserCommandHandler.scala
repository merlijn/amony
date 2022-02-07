package nl.amony.actor.user

import akka.persistence.typed.scaladsl.Effect
import nl.amony.actor.user.UserEventSourcing.{UserAdded, UserEvent}
import nl.amony.actor.user.UserProtocol.{UpsertUser, UserCommand}

object UserCommandHandler {

  case class User(id: String, passwordHash: String)
  case class UserState(users: Map[String, User])

  def apply(hashAlgo: String => String)(state: UserState, cmd: UserCommand): Effect[UserEvent, UserState] = {
    cmd match {
      case UpsertUser(email, password, sender) =>
        Effect
          .persist(UserAdded(email, password))
          .thenReply(sender)(_ => true)
    }
  }
}
