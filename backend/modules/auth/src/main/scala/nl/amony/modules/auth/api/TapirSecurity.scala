package nl.amony.modules.auth.api

import cats.Functor
import cats.data.EitherT
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint

import nl.amony.platform.tapir.dsl.Routes
import nl.amony.platform.tapir.dsl.error.SecurityError

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
