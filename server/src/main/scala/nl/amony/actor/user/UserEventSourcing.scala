package nl.amony.actor.user

import nl.amony.actor.user.UserCommandHandler.{User, UserState}

object UserEventSourcing {

  sealed trait UserEvent

  case class UserAdded(id: String, passwordHash: String) extends UserEvent

  def apply(state: UserState, event: UserEvent): UserState = event match {

    case UserAdded(id: String, passwordHash) =>
      state.copy(state.users + (id -> User(id, passwordHash)))
  }
}
