package nl.amony.modules.auth.api

import cats.Functor
import cats.data.EitherT
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint

import nl.amony.platform.tapir.dsl.Routes
import nl.amony.platform.tapir.dsl.error.SecurityError

def securedServerLogic[F[_], E >: SecurityError, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Option[Permission] = None
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F], security: ApiSecurity): Unit =
  val serverEndpoint = endpoint.serverSecurityLogicPure[AuthToken, F](security.authorize(requiredPermission)).serverLogic(logic)
  registry.add(serverEndpoint)

def securedServerLogic[F[_], E >: SecurityError, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Permission
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F], security: ApiSecurity): Unit =
  securedServerLogic(endpoint, Some(requiredPermission))(logic)

def securedServerLogic[F[_], E >: SecurityError, SI, I, O](
  endpoint: Endpoint[SI, I, E, O, Fs2Streams[F]],
  authorize: SI => Either[SecurityError, AuthToken]
)(logic: AuthToken => I => F[Either[E, O]])(using registry: Routes[F]): Unit =
  val serverEndpoint = endpoint.serverSecurityLogicPure[AuthToken, F](authorize).serverLogic(logic)
  registry.add(serverEndpoint)

def securedServerLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Option[Permission] = None
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F], security: ApiSecurity): Unit =
  securedServerLogic(endpoint, requiredPermission)(auth => input => logic(auth)(input).leftMap[E](identity).value)

def securedServerLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, I, O](
  endpoint: Endpoint[SecurityInput, I, E, O, Fs2Streams[F]],
  requiredPermission: Permission
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F], security: ApiSecurity): Unit =
  securedServerLogicT(endpoint, Some(requiredPermission))(logic)

def securedServerLogicT[F[_]: Functor, E >: SecurityError, LogicError <: E, SI, I, O](
  endpoint: Endpoint[SI, I, E, O, Fs2Streams[F]],
  authorize: SI => Either[SecurityError, AuthToken]
)(logic: AuthToken => I => EitherT[F, LogicError, O])(using registry: Routes[F]): Unit =
  securedServerLogic(endpoint, authorize)(auth => input => logic(auth)(input).leftMap[E](identity).value)
