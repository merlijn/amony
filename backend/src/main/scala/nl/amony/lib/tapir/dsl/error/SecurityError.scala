package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 401/403 errors. Intentionally a plain enum with no detail: a security error should not tell
 * the caller *why* a request was rejected. Include it in an endpoint's error union, e.g.
 * `ErrorResponse[SecurityError | BadRequestError]`.
 */
enum SecurityError extends ApiErrorLike:
  case Unauthorized
  case Forbidden

  def statusCode: StatusCode = this match
    case Unauthorized => StatusCode.Unauthorized
    case Forbidden    => StatusCode.Forbidden

  def code: String = this match
    case Unauthorized => "unauthorized"
    case Forbidden    => "forbidden"

  def message: String = this match
    case Unauthorized => "Authentication is required"
    case Forbidden    => "You do not have permission to perform this action"

object SecurityError:
  given ErrorVariants[SecurityError] = ErrorVariants.fromValues(SecurityError.values.toList)
