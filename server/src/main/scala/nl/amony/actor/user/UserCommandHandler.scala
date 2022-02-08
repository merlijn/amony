package nl.amony.actor.user

import akka.persistence.typed.scaladsl.Effect
import nl.amony.actor.user.UserEventSourcing.{UserAdded, UserEvent}
import nl.amony.actor.user.UserProtocol.{UpsertUser, User, UserCommand}

object UserCommandHandler {

  case class UserState(users: Map[String, User])

  def apply(hashAlgorithm: String => String)(state: UserState, cmd: UserCommand): Effect[UserEvent, UserState] = {
    cmd match {
      case UpsertUser(email, password, sender) =>

        state.users.values.find(_.email == email) match {
          case None =>
            val hashedPassword = hashAlgorithm(password)
            val uuid = java.util.UUID.randomUUID().toString

            Effect
              .persist(UserAdded(uuid, email, hashedPassword))
              .thenReply(sender)(_ => true)

          case Some(_) =>

            Effect.reply(sender)(false)
        }
    }
  }
}
