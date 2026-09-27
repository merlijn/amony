package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 500 error, deliberately carrying no detail: `code` and `message` are fixed and generic so
 * that a failure's internals are never leaked to the caller. The real cause should be logged instead.
 */
enum InternalServerError extends ApiErrorLike:
  case Unexpected

  def statusCode: StatusCode = StatusCode.InternalServerError
  def code: String           = "internal_server_error"
  def message: String        = "Internal server error"

object InternalServerError:
  given ErrorVariants[InternalServerError] = ErrorVariants.fromValues(InternalServerError.values.toList)
