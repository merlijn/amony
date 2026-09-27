package nl.amony.lib.tapir.dsl.error

import sttp.model.StatusCode

/**
 * Standard 500 error. Like [[BadRequestError]] the `code` and `message` are chosen by the server logic,
 * so every internal failure shares one variant (and the [[ErrorBody]] JSON shape). Prefer the generic
 * defaults: a 500 body should not leak internals to the caller — log the real cause instead.
 */
final case class InternalServerError(code: String = "internal_server_error", message: String = "Internal server error") extends ApiErrorLike:
  def statusCode: StatusCode = StatusCode.InternalServerError

object InternalServerError:
  given FromBody[InternalServerError]      = body => InternalServerError(body.code, body.message)
  given ErrorVariants[InternalServerError] = ErrorVariants.single[InternalServerError](StatusCode.InternalServerError)
