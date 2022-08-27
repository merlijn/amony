package nl.amony.service.auth

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.service.auth.actor.UserCommandHandler.UserState
import nl.amony.service.auth.actor.UserEventSourcing.UserEvent
import nl.amony.service.auth.actor.UserProtocol._
import nl.amony.service.auth.actor.{UserCommandHandler, UserEventSourcing}
import nl.amony.service.auth.api.{AuthServiceGrpc, Credentials, LoginResponse, UpsertUserRequest}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object AuthServiceImpl {
  def behavior(): Behavior[UserCommand] = {

    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(UserCommand.serviceKey, context.self)

      EventSourcedBehavior[UserCommand, UserEvent, UserState](
        persistenceId = PersistenceId.ofUniqueId("auth-users"),
        emptyState = UserState(Map.empty),
        commandHandler = UserCommandHandler.apply(str => str, new TokenManager(JwtConfig("secret", 15.minutes))),
        eventHandler = UserEventSourcing.apply
      )
    }
  }
}

class AuthServiceImpl(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) with AuthServiceGrpc.AuthService {

  import pureconfig.generic.auto._
  val config = loadConfig[AuthConfig]("amony.auth")

  insertUser(UpsertUserRequest(config.adminUsername, config.adminPassword))

  override def login(request: Credentials): Future[LoginResponse] =
    ask[UserCommand, AuthenticationResponse](ref => Authenticate(request.username, request.password, ref)).map {
      case Authentication(userId, token) => api.Authentication(userId, token)
      case InvalidCredentials            => api.InvalidCredentials()
    }

  override def insertUser(request: UpsertUserRequest): Future[api.User] = {
    ask[UserCommand, User](ref => UpsertUser(request.externalId, request.password, ref)).map { user =>
      api.User(user.id, user.email, user.passwordHash)
    }
  }

  override def getByExternalId(request: api.GetByExternalId): Future[api.User] = ???
}