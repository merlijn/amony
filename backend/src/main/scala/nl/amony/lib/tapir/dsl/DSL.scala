package nl.amony.lib.tapir.dsl

import scala.collection.mutable.ArrayBuffer

import cats.Functor
import cats.data.EitherT
import cats.effect.kernel.Async
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.modules.auth.api.{ApiSecurity, AuthToken, Permission, SecurityError, SecurityInput}

class Routes[F[_]]:
  private val endpoints = new ArrayBuffer[ServerEndpoint[Fs2Streams[F], F]]

  def add(endpoint: ServerEndpoint[Fs2Streams[F], F]): Unit = endpoints += endpoint

  def toList: List[ServerEndpoint[Fs2Streams[F], F]] = endpoints.toList

def routes[F[_]: Async](serverOptions: Http4sServerOptions[F])(init: Routes[F] ?=> Unit): HttpRoutes[F] =
  given registry: Routes[F] = Routes[F]()
  init
  Http4sServerInterpreter[F](serverOptions).toRoutes(registry.toList)

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
