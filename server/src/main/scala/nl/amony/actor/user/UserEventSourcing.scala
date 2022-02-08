package nl.amony.actor.user

import nl.amony.actor.user.UserCommandHandler.UserState
import nl.amony.actor.user.UserProtocol.User

object UserEventSourcing {

  sealed trait UserEvent

  case class UserAdded(id: String, email: String, passwordHash: String) extends UserEvent

  def apply(state: UserState, event: UserEvent): UserState = event match {

    case UserAdded(id, email, passwordHash) =>
      state.copy(state.users + (id -> User(id, email, passwordHash)))
  }
}
