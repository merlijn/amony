package nl.amony.modules.auth.api

import sttp.model.Method
import sttp.tapir.*

case class SecurityInput(accessToken: Option[String], xsrfCookie: Option[String], xXsrfHeader: Option[String], method: Method)

/**
 * The host the request was made with: X-Forwarded-Host (set by the reverse proxy) wins over Host.
 * Either may be a comma-separated list, of which the first entry is used.
 */
def effectiveHost(xForwardedHost: Option[String], host: Option[String]): String =
  def first(value: Option[String]) = value.map(_.split(",").head.trim).filter(_.nonEmpty)
  first(xForwardedHost).orElse(first(host)).getOrElse("")

/** The origin the client used to reach the backend, derived from the request. */
case class RequestOrigin(scheme: String, host: String):
  def callbackUri(provider: String): String = s"$scheme://$host/api/auth/callback/$provider"

  def rootUri: String = s"$scheme://$host"

val requestOrigin: EndpointInput[RequestOrigin] =
  extractFromRequest { request =>
    def first(value: Option[String]): Option[String] = value.map(_.split(",").head.trim).filter(_.nonEmpty)

    val host   = effectiveHost(request.header("X-Forwarded-Host"), request.header("Host"))
    val scheme = first(request.header("X-Forwarded-Proto")).getOrElse("http")
    RequestOrigin(scheme, host)
  }

val securityInput: EndpointInput[SecurityInput] =
  cookie[Option[String]](authCookieName)
    .and(cookie[Option[String]]("XSRF-TOKEN"))
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))
    .and(extractFromRequest(_.method))
    .mapTo[SecurityInput]
