package nl.amony.lib.tapir.dsl

import scala.collection.mutable.ArrayBuffer

import sttp.tapir.{AnyEndpoint, Endpoint}

trait RoutesModule:
  private val registeredEndpoints = ArrayBuffer.empty[AnyEndpoint]

  protected def register[SecurityInput, Input, ErrorOutput, Output, Capabilities](
    endpoint: Endpoint[SecurityInput, Input, ErrorOutput, Output, Capabilities]
  ): Endpoint[SecurityInput, Input, ErrorOutput, Output, Capabilities] =
    registeredEndpoints += endpoint
    endpoint

  def endpoints: List[AnyEndpoint] = registeredEndpoints.toList
