package nl.amony.modules.auth.api

import sttp.model.Method
import sttp.tapir.*

import nl.amony.lib.tapir.dsl.error.ErrorVariants.*
import nl.amony.lib.tapir.dsl.error.{BadRequestError, ErrorResponse, ErrorVariants, InternalServerError, NotFoundError, SecurityError}

case class SecurityInput(accessToken: Option[String], xsrfCookie: Option[String], xXsrfHeader: Option[String], method: Method)

/**
 * The host the request was made with: X-Forwarded-Host (set by the reverse proxy) wins over Host.
 * Either may be a comma-separated list, of which the first entry is used.
 */
def effectiveHost(xForwardedHost: Option[String], host: Option[String]): String =
  def first(value: Option[String]) = value.map(_.split(",").head.trim).filter(_.nonEmpty)
  first(xForwardedHost).orElse(first(host)).getOrElse("")

/**
 * The origin the client used to reach the backend, derived from the request.
 *
 * Behind the reverse proxy X-Forwarded-Host/X-Forwarded-Proto are set; when the backend is
 * reached directly the Host header is used instead. This is used to build the OAuth
 * redirect_uri so it always matches the origin the browser is actually on, no matter which
 * frontend host (or hostname) the request came in through.
 */
case class RequestOrigin(scheme: String, host: String):
  def callbackUri(provider: String): String = s"$scheme://$host/api/auth/callback/$provider"

  /** The origin the client is on, used as `post_logout_redirect_uri` for federated logout. */
  def rootUri: String = s"$scheme://$host/"

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

/** 401/403 for endpoints secured with [[ApiSecurity]]. */
val securityErrors: EndpointOutput[SecurityError] = ErrorResponse.of[SecurityError].output

/** Error set shared by the resource, collection and search endpoints (401/403, 404, 400). */
given standardErrorVariants: ErrorVariants[SecurityError | NotFoundError | BadRequestError] =
  summon[ErrorVariants[SecurityError]].or(summon[ErrorVariants[NotFoundError]]).or(summon[ErrorVariants[BadRequestError]])

val standardErrorOutput: EndpointOutput[SecurityError | NotFoundError | BadRequestError] =
  ErrorResponse.of[SecurityError | NotFoundError | BadRequestError].output

/** Error set for the auth callback (401/403, 404, 400, 500). */
given callbackErrorVariants: ErrorVariants[SecurityError | NotFoundError | BadRequestError | InternalServerError] =
  summon[ErrorVariants[SecurityError | NotFoundError | BadRequestError]].or(summon[ErrorVariants[InternalServerError]])

val callbackErrorOutput: EndpointOutput[SecurityError | NotFoundError | BadRequestError | InternalServerError] =
  ErrorResponse.of[SecurityError | NotFoundError | BadRequestError | InternalServerError].output
