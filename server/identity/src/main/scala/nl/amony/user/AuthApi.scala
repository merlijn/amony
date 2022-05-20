package nl.amony.user

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.Timeout
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.user.AuthApi.authServiceKey
import nl.amony.user.actor.UserCommandHandler.UserState
import nl.amony.user.actor.UserEventSourcing.UserEvent
import nl.amony.user.actor.UserProtocol._
import nl.amony.user.actor.UserCommandHandler
import nl.amony.user.actor.UserEventSourcing
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtCirce
import pdi.jwt.JwtClaim

import java.time.Instant
import scala.concurrent.Future
import scala.util.Try

object AuthApi {
  def userBehaviour(): Behavior[UserCommand] = {

    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(authServiceKey, context.self)

      EventSourcedBehavior[UserCommand, UserEvent, UserState](
        persistenceId  = PersistenceId.ofUniqueId("amony-users"),
        emptyState     = UserState(Map.empty),
        commandHandler = UserCommandHandler.apply(str => str),
        eventHandler   = UserEventSourcing.apply
      )
    }
  }

  val authServiceKey = ServiceKey[UserCommand]("authService")
}

class AuthApi(override val system: ActorSystem[Nothing], override val askTimeout: Timeout, config: AuthConfig) extends AkkaServiceModule[UserCommand] {

  val expirationInSeconds = config.jwt.tokenExpiration.toSeconds
  val algo                = JwtAlgorithm.HS256 // TODO get from config

  override val serviceKey: ServiceKey[UserCommand] = authServiceKey

  def upsertUser(userName: String, password: String)(implicit timeout: Timeout): Future[User] =
    askService[User](ref => UpsertUser(userName, password, ref))

  def login(username: String, password: String)(implicit timeout: Timeout): Future[AuthenticationResponse] =
    askService[AuthenticationResponse](ref => Authenticate(username, password, ref))

  def createToken(userId: String): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt   = Some(Instant.now.getEpochSecond)
    ) + ("admin", true) + ("userId", userId)

    val token = JwtCirce.encode(claim, config.jwt.secretKey, algo)

    token
  }

  def decodeToken(token: String): Try[JwtClaim] = {
    JwtCirce.decode(token, config.jwt.secretKey, Seq(algo))
  }
}
