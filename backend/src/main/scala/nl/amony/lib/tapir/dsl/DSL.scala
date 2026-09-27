package nl.amony.lib.tapir.dsl

import scala.collection.mutable.ArrayBuffer

import cats.Functor
import cats.data.EitherT
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint

import nl.amony.lib.tapir.dsl.error.SecurityError
import nl.amony.modules.auth.api.{ApiSecurity, AuthToken, Permission, SecurityInput}

/** A module's routes as a framework-independent list of tapir server endpoints. */
type ServerEndpoints[F[_]] = List[ServerEndpoint[Fs2Streams[F], F]]

class Routes[F[_]]:
  private val endpoints = new ArrayBuffer[ServerEndpoint[Fs2Streams[F], F]]

  def add(endpoint: ServerEndpoint[Fs2Streams[F], F]): Unit = endpoints += endpoint

  def toList: ServerEndpoints[F] = endpoints.toList

def routes[F[_]](init: Routes[F] ?=> Unit): ServerEndpoints[F] =
  given registry: Routes[F] = Routes[F]()
  init
  registry.toList

def serverLogic[F[_], E, I, O](
  endpoint: Endpoint[Unit, I, E, O, Fs2Streams[F]]
)(logic: I => F[Either[E, O]])(using registry: Routes[F]): Unit =
  registry.add(endpoint.serverLogic(logic))

def serverLogic[F[_], E >: SecurityError, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Option[Permission] = None
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F], security: ApiSecurity): Unit =
  val serverEndpoint = endpoint.serverSecurityLogicPure[AuthToken, F](security.authorize(requiredPermission)).serverLogic(logic)
  registry.add(serverEndpoint)

def serverLogic[F[_], E >: SecurityError, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Permission
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F], security: ApiSecurity): Unit =
  serverLogic(endpoint, Some(requiredPermission))(logic)

def serverLogic[F[_], E >: SecurityError, SI, I, O](
  endpoint: Endpoint[SI, I, E, O, Fs2Streams[F]],
  authorize: SI => Either[SecurityError, AuthToken]
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F]): Unit =
  val serverEndpoint = endpoint.serverSecurityLogicPure[AuthToken, F](authorize).serverLogic(logic)
  registry.add(serverEndpoint)

def serverLogicT[F[_]: Functor, E, LogicError <: E, I, O](
  endpoint: Endpoint[Unit, I, E, O, Fs2Streams[F]]
)(logic: I => EitherT[F, LogicError, O])(using registry: Routes[F]): Unit =
  serverLogic(endpoint)(input => logic(input).leftMap[E](identity).value)

def serverLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Option[Permission] = None
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F], security: ApiSecurity): Unit =
  serverLogic(endpoint, requiredPermission)(auth => input => logic(auth)(input).leftMap[E](identity).value)

def serverLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Permission
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F], security: ApiSecurity): Unit =
  serverLogicT(endpoint, Some(requiredPermission))(logic)

def serverLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, SI, I, O](
  endpoint: Endpoint[SI, I, E, O, Fs2Streams[F]],
  authorize: SI => Either[SecurityError, AuthToken]
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F]): Unit =
  serverLogic(endpoint, authorize)(auth => input => logic(auth)(input).leftMap[E](identity).value)
