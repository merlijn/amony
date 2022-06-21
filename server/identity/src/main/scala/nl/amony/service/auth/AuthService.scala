package nl.amony.service.auth

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.Timeout
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.service.auth.actor.{UserCommandHandler, UserEventSourcing}
import nl.amony.service.auth.actor.UserCommandHandler.UserState
import nl.amony.service.auth.actor.UserEventSourcing.UserEvent
import nl.amony.service.auth.actor.UserProtocol._
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

import java.time.Instant
import scala.concurrent.Future
import scala.util.Try

object AuthService {
  def behavior(): Behavior[UserCommand] = {

    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(UserCommand.serviceKey, context.self)

      EventSourcedBehavior[UserCommand, UserEvent, UserState](
        persistenceId = PersistenceId.ofUniqueId("auth-users"),
        emptyState = UserState(Map.empty),
        commandHandler = UserCommandHandler.apply(str => str),
        eventHandler = UserEventSourcing.apply
      )
    }
  }
}

class AuthService(system: ActorSystem[Nothing]) extends AkkaServiceModule[UserCommand](system) {

  import pureconfig.generic.auto._
  val config = loadConfig[AuthConfig]("amony.auth")

  val expirationInSeconds = config.jwt.tokenExpiration.toSeconds
  val algo                = JwtAlgorithm.HS256 // TODO get from config

  def upsertUser(userName: String, password: String): Future[User] =
    askService[User](ref => UpsertUser(userName, password, ref))

  def login(username: String, password: String): Future[AuthenticationResponse] =
    askService[AuthenticationResponse](ref => Authenticate(username, password, ref))

  private[auth] def createToken(userId: String): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt   = Some(Instant.now.getEpochSecond)
    ) + ("userId", userId)

    val token = JwtCirce.encode(claim, config.jwt.secretKey, algo)

    token
  }

  def decodeToken(token: String): Try[JwtClaim] = {
    JwtCirce.decode(token, config.jwt.secretKey, Seq(algo))
  }
}
