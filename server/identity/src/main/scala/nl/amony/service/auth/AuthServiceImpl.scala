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
import nl.amony.service.auth.actor.{TokenManager, UserCommandHandler, UserEventSourcing}
import nl.amony.service.auth.api.AuthService
import nl.amony.service.auth.api.AuthService.{AuthServiceGrpc, GetByExternalId, LoginResponse, UpsertUserRequest}

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

class AuthServiceImpl(system: ActorSystem[Nothing]) extends AkkaServiceModule[UserCommand](system) with AuthServiceGrpc.AuthService {

  import pureconfig.generic.auto._
  val config = loadConfig[AuthConfig]("amony.auth")

  def upsertUser(userName: String, password: String): Future[User] =
    askService[User](ref => UpsertUser(userName, password, ref))

  override def login(request: AuthService.Credentials): Future[LoginResponse] =
    askService[AuthenticationResponse](ref => Authenticate(request.username, request.password, ref)).map {
      case Authentication(userId, token) => AuthService.Authentication(userId, token)
      case InvalidCredentials            => AuthService.InvalidCredentials()
    }

  override def insertUser(request: UpsertUserRequest): Future[AuthService.User] = {
    askService[User](ref => UpsertUser(request.externalId, request.password, ref)).map { user =>
      AuthService.User(user.id, user.email, user.passwordHash)
    }
  }

  override def getByExternalId(request: GetByExternalId): Future[AuthService.User] = ???
}
