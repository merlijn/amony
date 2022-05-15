package nl.amony.user

import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.util.Timeout
import nl.amony.user.UserApi.userServiceKey
import nl.amony.user.actor.{UserCommandHandler, UserEventSourcing}
import nl.amony.user.actor.UserCommandHandler.UserState
import nl.amony.user.actor.UserEventSourcing.UserEvent
import nl.amony.user.actor.UserProtocol.{Authenticate, AuthenticationResponse, UpsertUser, User, UserCommand}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object UserApi {
  def userBehaviour(): Behavior[UserCommand] = {

    Behaviors.setup { context =>

      context.system.receptionist ! Receptionist.Register(userServiceKey, context.self)

      EventSourcedBehavior[UserCommand, UserEvent, UserState](
        persistenceId  = PersistenceId.ofUniqueId("amony-users"),
        emptyState     = UserState(Map.empty),
        commandHandler = UserCommandHandler.apply(str => str),
        eventHandler   = UserEventSourcing.apply
      )
    }
  }

  val userServiceKey = ServiceKey[UserCommand]("userService")
}

class UserApi(system: ActorSystem[Nothing], config: AuthConfig) {

  private implicit def ec: ExecutionContext = system.executionContext
  private implicit def scheduler: Scheduler = system.scheduler

  private def userRef()(implicit timeout: Timeout): Future[ActorRef[UserCommand]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(userServiceKey, ref))(timeout, system.scheduler)
      .map( _.serviceInstances(userServiceKey).head)

  def upsertUser(userName: String, password: String)(implicit timeout: Timeout): Future[User] = {
    userRef().flatMap(_.ask[User](ref => UpsertUser(userName, password, ref)))
  }

  def login(username: String, password: String)(implicit timeout: Timeout): Future[AuthenticationResponse] =
    userRef().flatMap(_.ask[AuthenticationResponse](ref => Authenticate(username, password, ref)))

  val expirationInSeconds = config.jwt.tokenExpiration.toSeconds
  val algo = JwtAlgorithm.HS256 // TODO get from config

  def createToken(userId: String): String = {

    val claim = JwtClaim(
      expiration = Some(Instant.now.plusSeconds(expirationInSeconds).getEpochSecond),
      issuedAt = Some(Instant.now.getEpochSecond)
    ) + ("admin", true) + ("userId", userId)

    val token = JwtCirce.encode(claim, config.jwt.secretKey, algo)

    token
  }

  def decodeToken(token: String): Try[JwtClaim] = {
    JwtCirce.decode(token, config.jwt.secretKey, Seq(algo))
  }
}
