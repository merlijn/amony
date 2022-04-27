package nl.amony.actor.user

import akka.persistence.typed.scaladsl.Effect
import nl.amony.actor.user.UserEventSourcing.{UserAdded, UserEvent}
import nl.amony.actor.user.UserProtocol.{Authenticate, Authentication, InvalidCredentials, UpsertUser, User, UserCommand}

object UserCommandHandler {

  case class UserState(users: Map[String, User])

  def apply(passwordHasher: String => String)(state: UserState, cmd: UserCommand): Effect[UserEvent, UserState] = {

    def getByEmail(email: String): Option[User] = state.users.values.find(_.email == email)

    cmd match {
      case UpsertUser(email, password, sender) =>

        getByEmail(email) match {
          case None =>
            val hashedPassword = passwordHasher(password)
            val uuid = java.util.UUID.randomUUID().toString

            Effect
              .persist(UserAdded(uuid, email, hashedPassword))
              .thenReply(sender)(_ => true)

          case Some(_) =>

            Effect.reply(sender)(false)
        }
      case Authenticate(email, password, sender) =>

        val response =
          getByEmail(email) match {
            case None       => InvalidCredentials
            case Some(user) =>
              if (user.passwordHash == passwordHasher(password))
                Authentication(user.id)
              else
                InvalidCredentials
          }

        Effect.reply(sender)(response)
    }
  }
}
