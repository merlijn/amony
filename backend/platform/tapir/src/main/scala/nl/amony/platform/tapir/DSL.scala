package nl.amony.platform.tapir

import scala.collection.mutable.ArrayBuffer

import cats.Functor
import cats.data.EitherT
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint

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

def serverLogicT[F[_]: Functor, E, LogicError <: E, I, O](
  endpoint: Endpoint[Unit, I, E, O, Fs2Streams[F]]
)(logic: I => EitherT[F, LogicError, O])(using registry: Routes[F]): Unit =
  serverLogic(endpoint)(input => logic(input).leftMap[E](identity).value)
